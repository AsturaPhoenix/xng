package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.google.common.collect.Iterators;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
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
  public static final float THRESHOLD = 1;
  public static final float AUTOREINFORCEMENT = .05f;
  public static final long DEFAULT_DECAY = 100;

  public class ContextualState {
    private final Context context;
    private final Subject<Observable<Long>> rxEvaluations;
    private final Map<Node, ArrayDeque<Evaluation>> evaluations = new WeakHashMap<>();

    public ContextualState(final Context context) {
      this.context = context;
      rxEvaluations = PublishSubject.create();
      Observable.switchOnNext(rxEvaluations).subscribe(t -> rxOutput.onNext(new Node.Activation(context, t)));
    }

    public void reinforce(Optional<Long> time, Optional<Long> decayPeriod, float weight) {
      try (val lock = new DebugLock.Multiple(context.mutex(), Synapse.this.lock)) {
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
    private final WeakReference<Node> incoming;
    private final Disposable subscription;

    private Profile(final Node incoming) {
      coefficient = new ThresholdDistribution(0);
      this.incoming = new WeakReference<>(incoming);
      decayPeriod = DEFAULT_DECAY;
      subscription = incoming.rxActivate().subscribe(this::onActivate);
    }

    private void onActivate(final Node.Activation activation) {
      val ref = activation.context.new Ref();

      final ContextualState state = activation.context.synapseState(Synapse.this);
      try (val lock = new DebugLock.Multiple(activation.context.mutex(), Synapse.this.lock)) {
        final float before = getValue(activation.context, activation.timestamp);
        final float value = coefficient.generate();
        coefficient.add(value, AUTOREINFORCEMENT);
        state.evaluations.computeIfAbsent(incoming.get(), node -> new ArrayDeque<>())
            .add(new Evaluation(activation.timestamp, value));
        state.rxEvaluations
            .onNext(evaluate(activation.context, activation.timestamp, value - before).doFinally(ref::close));
      }
    }

    public float getValue(final Context context, final long time) {
      val incoming = incoming.get();
      if (incoming == null)
        // This can only happen in the pathological usage where a caller holds onto a
        // Profile instance beyond the lifetime of the incoming Node.
        throw new IllegalStateException("Profile instance has expired.");

      // TODO(rosswang): it may be an interesting simplification to restrict that
      // nodes may only be activated once per context. Then we might be able to get
      // rid of decay, but it would have implications for temporal processing (in
      // particular we'd force discrete time steps).

      final Evaluation lastEvaluation = getPrecedingEvaluation(context, incoming, time);
      return lastEvaluation == null ? 0 : extrapolateEvaluation(lastEvaluation, time);
    }

    public float extrapolateEvaluation(final Evaluation evaluation, final long time) {
      final long dt = time - evaluation.time;
      return dt < decayPeriod ? evaluation.value * (1 - (float) dt / decayPeriod) : 0;
    }
  }

  /**
   * This lock should be used carefully. Although contextual synapse methods
   * wouldn't seem to need to lock the context, they can due to lazy creation of
   * node states. In those cases, if we aren't already holding the lock, we can
   * deadlock against reinforcement, which locks in reverse order. Thus contextual
   * methods should first lock the context.
   * 
   * This is fragile and should probably be reworked at some point.
   */
  private final Lock lock = new ReentrantLock();

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
  private Observable<Long> evaluate(final Context context, final long time, final float margin) {
    final float value = getValue(context, time);

    if (value >= THRESHOLD) {
      // Only signal on transitions.
      return value - margin < THRESHOLD ? Observable.just(time) : Observable.empty();
    } else {
      final long nextThreshold = getNextThreshold(context, time);
      if (nextThreshold == Long.MAX_VALUE) {
        return Observable.empty();
      } else {
        return Observable.timer(nextThreshold - time, TimeUnit.MILLISECONDS).map(z -> nextThreshold);
      }
    }
  }

  public float getValue(final Context context, final long time) {
    try (val lock = new DebugLock(lock)) {
      return inputs.values().stream().map(profile -> profile.getValue(context, time)).reduce(0.f, Float::sum);
    }
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

    for (val entry : inputs.entrySet()) {
      val profile = entry.getValue();
      val decayProfile = new EvaluationDecayProfile();
      decayProfile.evaluation = getPrecedingEvaluation(context, entry.getKey(), time);
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

  private void writeObject(final ObjectOutputStream o) throws IOException {
    try (val lock = new DebugLock(lock)) {
      o.defaultWriteObject();
      o.writeInt(inputs.size());
      for (final Entry<Node, Profile> entry : inputs.entrySet()) {
        o.writeObject(entry.getKey());
        o.writeObject(entry.getValue().coefficient);
        o.writeLong(entry.getValue().decayPeriod);
      }
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

  public Profile profile(final Node node) {
    try (val lock = new DebugLock(lock)) {
      return inputs.computeIfAbsent(node, Profile::new);
    }
  }

  public Synapse setCoefficient(final Node node, final float coefficient) {
    val profile = profile(node);
    profile.coefficient.set(coefficient);
    return this;
  }

  public float getCoefficient(final Node node) {
    try (val lock = new DebugLock(lock)) {
      final Profile profile = inputs.get(node);
      return profile == null ? 0 : profile.coefficient.getMode();
    }
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

  public long getDecayPeriod(final Node node) {
    try (val lock = new DebugLock(lock)) {
      final Profile profile = inputs.get(node);
      return profile == null ? 0 : profile.decayPeriod;
    }
  }

  public void dissociate(final Node node) {
    try (val lock = new DebugLock(lock)) {
      final Profile profile = inputs.remove(node);
      if (profile != null) {
        profile.subscription.dispose();
      }
    }
  }

  public Evaluation getLastEvaluation(final Context context, final Node incoming) {
    return getPrecedingEvaluation(context, incoming, Long.MAX_VALUE);
  }

  public Evaluation getPrecedingEvaluation(final Context context, final Node incoming, final long time) {
    try (val lock = new DebugLock(lock)) {
      final ArrayDeque<Evaluation> evaluations = context.synapseState(this).evaluations.get(incoming);
      if (evaluations == null)
        return null;

      return Iterators.tryFind(evaluations.descendingIterator(), e -> e.time <= time).orNull();
    }
  }

  public Map<Node, List<Evaluation>> getRecentEvaluations(final Context context, final long since) {
    try (val lock = new DebugLock(lock)) {
      val evaluations = context.synapseState(this).evaluations;
      final Map<Node, List<Evaluation>> recent = new HashMap<>();
      for (val entry : evaluations.entrySet()) {
        final List<Evaluation> recentForPrior = new ArrayList<>();
        recent.put(entry.getKey(), recentForPrior);

        val it = entry.getValue().descendingIterator();
        while (it.hasNext()) {
          val evaluation = it.next();

          if (evaluation.time < since)
            break;

          recentForPrior.add(evaluation);
        }
      }
      return recent;
    }
  }

  @Override
  public String toString() {
    try (val lock = new DebugLock(lock)) {
      return inputs.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue().getCoefficient())
          .collect(Collectors.joining("\n"));
    }
  }
}
