package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Iterators;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Synchronized;
import lombok.ToString;
import lombok.val;

/**
 * Represents the incoming logical junction of input node signals towards a
 * specific output node.
 * 
 * Minimal effort is made to preserve activation across serialization boundaries
 * since behavior while the system is down is discontinuous anyway. In the
 * future, it is likely that either we will switch to relative time and fully
 * support serialization or else completely clear activation on deserialization.
 * 
 * Concurrency note: Although Synapse has a lot of state both contextual and
 * global, all synchronization is done through a common mutex to avoid deadlock
 * in cases where mutex nesting may vary.
 */
public class Synapse implements Serializable {
  private static final long serialVersionUID = 1779165354354490167L;

  public static final long DEBOUNCE_PERIOD = 2;
  private static final float DECAY_MARGIN = .2f;
  private static final float THRESHOLD = 1;

  public class ContextualState {
    private final Subject<Long> rxEvaluate;
    private final Map<Node, ArrayDeque<Evaluation>> evaluations = new WeakHashMap<>();

    public ContextualState(final Context context) {
      rxEvaluate = PublishSubject.create();
      // addRef: Profile::onActivate
      rxEvaluate.switchMap(t -> evaluate(context, t).doFinally(context::releaseRef))
          .subscribe(t -> rxOutput.onNext(new Node.Activation(context, t)));
    }

    public void reinforce(Optional<Long> time, Optional<Long> decayPeriod, float weight) {
      synchronized (Synapse.this.$lock) {
        for (final Entry<Node, ArrayDeque<Evaluation>> entry : evaluations.entrySet()) {
          final Profile profile = inputs.get(entry.getKey());
          for (final Evaluation evaluation : entry.getValue()) {
            final long t = evaluation.time;
            float wt = weight;
            if (time.isPresent()) {
              if (t > time.get())
                continue;
              if (decayPeriod.isPresent()) {
                long t0 = time.get() - decayPeriod.get();
                if (t <= t0)
                  continue;
                wt *= (float) (t - t0) / decayPeriod.get();
              }
            }

            profile.coefficient.add(evaluation.value, wt);
          }
        }
      }
    }
  }

  public class Profile {
    @Getter
    private Distribution coefficient;
    @Getter
    private long decayPeriod; // linear for now
    private final Node incoming;
    private final Disposable subscription;

    private Profile(final Node incoming) {
      this.coefficient = new ThresholdDistribution(0);
      this.incoming = incoming;
      resetDecay();
      subscription = incoming.rxActivate().subscribe(this::onActivate);
    }

    public void resetDecay() {
      // The default decay should be roughly proportional to the
      // refractory period of the source node as nodes with shorter
      // refractory periods are likely to be evoked more often, possibly
      // spuriously, and should thus get out of the way faster. By the
      // time the refractory period has elapsed and the node may thus be
      // activated again, we want this activation to be decayed by at the
      // decay margin.
      synchronized (Synapse.this.$lock) {
        decayPeriod = Math.max((long) (incoming.getRefractory() / DECAY_MARGIN), 1);
      }
    }

    private void onActivate(final Node.Activation activation) {
      // Schedules an evaluation in the appropriate context, which will
      // sum the incoming signals.
      // releaseRef: ContextualState::ContextualState
      activation.context.addRef();
      activation.context.synapseState(Synapse.this).rxEvaluate.onNext(activation.timestamp);
    }

    public float getValue(final Context context, final long time) {
      // TODO(rosswang): it may be an interesting simplification to restrict that
      // nodes may only be activated once per context. Then we might be able to get
      // rid of refractory periods and decays, but it would have implications for
      // temporal processing (in particular we'd force discrete time steps).

      synchronized (Synapse.this.$lock) {
        final long lastActivation = incoming.getLastActivation(context);
        if (lastActivation == 0)
          return 0;

        // The following is basically Synapse::getPrecedingEvaluation but with a
        // computeIfAbsent for the deque and the evaluation, and an extrapolation
        // afterwards.
        final ArrayDeque<Evaluation> evaluations = context.synapseState(Synapse.this).evaluations
            .computeIfAbsent(incoming, node -> new ArrayDeque<>());

        // We lazily re-evaluate inhibitory coefficients, so check whether we need to
        // re-evaluate now.
        if (evaluations.isEmpty() || evaluations.peekLast().time < lastActivation) {
          evaluations.add(new Evaluation(lastActivation, coefficient.generate()));
        }

        return Iterators.tryFind(evaluations.descendingIterator(), e -> e.time <= time)
            .transform(e -> extrapolateEvaluation(e, time)).or(0.f);
      }
    }

    public float extrapolateEvaluation(final Evaluation evaluation, final long time) {
      final long dt = time - evaluation.time;
      return dt < decayPeriod ? evaluation.value * (1 - (float) dt / decayPeriod) : 0;
    }
  }

  private transient Map<Node, Profile> inputs;
  private transient Subject<Node.Activation> rxOutput;

  public Synapse() {
    init();
  }

  private void init() {
    inputs = new WeakHashMap<>();
    rxOutput = PublishSubject.create();
  }

  @RequiredArgsConstructor
  @ToString
  @EqualsAndHashCode
  public static class Evaluation {
    public final long time;
    public final float value;
  }

  /**
   * Emits an activation signal or schedules a re-evaluation at a future time,
   * depending on current state.
   */
  @Synchronized
  private Observable<Long> evaluate(final Context context, final long time) {
    val value = getValue(context, time);
    if (value >= THRESHOLD) {
      return Observable.just(time);
    } else {
      final long nextThreshold = getNextThreshold(context, time);
      if (nextThreshold == Long.MAX_VALUE) {
        return Observable.empty();
      } else {
        return Observable.timer(nextThreshold - time, TimeUnit.MILLISECONDS).map(z -> nextThreshold);
      }
    }
  }

  @Synchronized
  public float getValue(final Context context, final long time) {
    return inputs.values().stream().map(profile -> profile.getValue(context, time)).reduce(0.f, Float::sum);
  }

  private static class EvaluationDecayProfile {
    Evaluation evaluation;
    float decayRate;
    long expiration;
  }

  /**
   * Gets the next time the synapse should activate if current conditions hold, or
   * {@link Long#MAX_VALUE} if an activation is not on the horizon.
   */
  private long getNextThreshold(final Context context, long time) {
    val decayProfiles = new ArrayList<EvaluationDecayProfile>(inputs.size());

    // We'll walk these back as profiles expire. If the roundoff error is too
    // severe, we can revisit.
    float totalValue = 0, totalDecayRate = 0;

    for (final Profile profile : inputs.values()) {
      val decayProfile = new EvaluationDecayProfile();
      decayProfile.evaluation = getPrecedingEvaluation(context, profile.incoming, time);
      if (decayProfile.evaluation == null)
        continue;

      decayProfile.expiration = decayProfile.evaluation.time + profile.decayPeriod;
      if (time >= decayProfile.expiration)
        continue;

      decayProfile.decayRate = decayProfile.evaluation.value / profile.decayPeriod;
      decayProfiles.add(decayProfile);

      totalValue += profile.extrapolateEvaluation(decayProfile.evaluation, time);
      totalDecayRate += decayProfile.decayRate;
    }

    // Descend by expiration; we'll pop from the back as evaluations expire.
    decayProfiles.sort((a, b) -> Long.compare(b.expiration, a.expiration));

    while (!decayProfiles.isEmpty()) {
      assert totalValue < THRESHOLD;
      val expiringProfile = decayProfiles.remove(decayProfiles.size() - 1);

      if (totalDecayRate < 0) {
        final long nextThresh = time + (long) Math.ceil((THRESHOLD - totalValue) / -totalDecayRate);
        if (nextThresh <= expiringProfile.expiration)
          return nextThresh;
      }

      totalValue -= (expiringProfile.expiration - time) * totalDecayRate;
      time = expiringProfile.expiration;
      totalDecayRate -= expiringProfile.decayRate;
    }
    return Long.MAX_VALUE;
  }

  public Observable<Node.Activation> rxActivate() {
    return rxOutput;
  }

  @Synchronized
  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.defaultWriteObject();
    o.writeInt(inputs.size());
    for (final Entry<Node, Profile> entry : inputs.entrySet()) {
      o.writeObject(entry.getKey());
      o.writeObject(entry.getValue().coefficient);
      o.writeLong(entry.getValue().decayPeriod);
    }
  }

  private void readObject(ObjectInputStream o) throws IOException, ClassNotFoundException {
    o.defaultReadObject();
    init();
    final int size = o.readInt();
    for (int i = 0; i < size; i++) {
      final Node node = (Node) o.readObject();
      final Profile profile = new Profile(node);
      profile.coefficient = (Distribution) o.readObject();
      profile.decayPeriod = o.readLong();
      inputs.put(node, profile);
    }
  }

  @Synchronized
  public Profile profile(final Node node) {
    return inputs.computeIfAbsent(node, Profile::new);
  }

  public Synapse setCoefficient(final Node node, final float coefficient) {
    val profile = profile(node);
    profile.coefficient.set(coefficient);
    return this;
  }

  @Synchronized
  public float getCoefficient(final Node node) {
    final Profile profile = inputs.get(node);
    return profile == null ? 0 : profile.coefficient.getMode();
  }

  /**
   * @param node      the input node
   * @param decayRate the linear signal decay period, in milliseconds from
   *                  activation to 0
   */
  public Synapse setDecayPeriod(final Node node, final long decayPeriod) {
    profile(node).decayPeriod = decayPeriod;
    return this;
  }

  @Synchronized
  public long getDecayPeriod(final Node node) {
    final Profile profile = inputs.get(node);
    return profile == null ? 0 : profile.decayPeriod;
  }

  @Synchronized
  public void dissociate(final Node node) {
    final Profile profile = inputs.remove(node);
    if (profile != null) {
      profile.subscription.dispose();
    }
  }

  @Synchronized
  public Evaluation getLastEvaluation(final Context context, final Node incoming) {
    final ArrayDeque<Evaluation> evaluations = context.synapseState(Synapse.this).evaluations.get(incoming);
    if (evaluations == null)
      return null;

    return evaluations.peekLast();
  }

  @Synchronized
  public Evaluation getPrecedingEvaluation(final Context context, final Node incoming, final long time) {
    final ArrayDeque<Evaluation> evaluations = context.synapseState(Synapse.this).evaluations.get(incoming);
    if (evaluations == null)
      return null;

    return Iterators.tryFind(evaluations.descendingIterator(), e -> e.time <= time).orNull();
  }
}
