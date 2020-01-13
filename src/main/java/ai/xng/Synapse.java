package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.ReplaySubject;
import io.reactivex.subjects.Subject;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * Represents the incoming logical junction of input node signals towards a
 * specific output node.
 * 
 * Minimal effort is made to preserve activation across serialization boundaries
 * since behavior while the system is down is discontinuous anyway. In the
 * future, it is likely that either we will switch to relative time and fully
 * support serialization or else completely clear activation on deserialization.
 */
public class Synapse implements Serializable {
  private static final long serialVersionUID = 1779165354354490167L;

  public static final long DEBOUNCE_PERIOD = 2;
  private static final float DECAY_MARGIN = .2f;
  private static final float THRESHOLD = 1;

  public class ContextualState {
    private final Subject<Long> rxEvaluate;
    private final Map<Profile, Evaluation> evaluations = Collections.synchronizedMap(new WeakHashMap<>());

    public ContextualState(final Context context) {
      rxEvaluate = PublishSubject.create();
      rxEvaluate.switchMap(t -> evaluate(context, t).doFinally(context::releaseRef)).subscribe(t -> {
        rxOutput.onNext(new Node.Activation(context, t));
      });
      context.lifetime().thenRun(rxEvaluate::onComplete);
    }
  }

  public class Profile {
    @Getter
    private Distribution coefficient;
    @Getter
    private long decayPeriod; // linear for now
    private final Node incoming;
    private Disposable subscription;

    private Profile(final Node incoming) {
      this.coefficient = new Distribution(1);
      this.incoming = incoming;
      resetDecay();
      updateSubscription();
    }

    public void resetDecay() {
      // The default decay should be roughly proportional to the
      // refractory period of the source node as nodes with shorter
      // refractory periods are likely to be evoked more often, possibly
      // spuriously, and should thus get out of the way faster. By the
      // time the refractory period has elapsed and the node may thus be
      // activated again, we want this activation to be decayed by at the
      // decay margin.
      decayPeriod = Math.max((long) (incoming.getRefractory() / DECAY_MARGIN), 1);
    }

    /**
     * Only subscriptions to positive coefficients are kept; negative coefficients
     * can still contribute to evaluation, but naturally they can never themselves
     * result in a signal being propagated so they do not require a subscription.
     */
    public void updateSubscription() {
      if (coefficient.getMax() > 0 && subscription == null) {
        subscription = incoming.rxActivate().subscribe(this::onActivate);
      } else if (coefficient.getMax() <= 0 && subscription != null) {
        subscription.dispose();
        subscription = null;
      }
    }

    private void onActivate(final Node.Activation activation) {
      // Schedules an evaluation in the appropriate context, which will
      // sum the incoming signals.
      activation.context.addRef();
      activation.context.synapseState(Synapse.this).rxEvaluate.onNext(activation.timestamp);
    }

    public float getValue(final Context context, final long time) {
      // We lazily re-evaluate inhibitory coefficients, so check whether
      // we need to re-evaluate now.
      long lastActivation = incoming.getLastActivation(context);
      long dt = Math.max(time - lastActivation, 0);

      if (dt >= decayPeriod) {
        return 0;
      }

      final ContextualState contextualState = context.synapseState(Synapse.this);
      // TODO(rosswang): it may be an interesting simplification to
      // restrict that nodes may only be activated once per context. Then
      // we might be able to get rid of refractory periods and decays, but
      // it would have implications for temporal processing (in particular
      // we'd force discrete time steps).
      final Evaluation lastEvaluation = contextualState.evaluations.get(this);
      final float v0;
      if (lastEvaluation == null || lastEvaluation.time != lastActivation) {
        v0 = coefficient.generate();
        contextualState.evaluations.put(this, new Evaluation(lastActivation, v0));
      } else {
        v0 = lastEvaluation.value;
      }
      return v0 * (1 - dt / (float) decayPeriod);
    }

    public float getLastCoefficient(final Context context) {
      return context.synapseState(Synapse.this).evaluations.get(this).value;
    }

    private long getZero(final Context context) {
      return incoming.getLastActivation(context) + decayPeriod;
    }
  }

  private transient Map<Node, Profile> inputs;

  private transient Subject<Node.Activation> rxOutput;
  private transient Subject<ContextualEvaluation> rxValue;

  public Synapse() {
    init();
  }

  private void init() {
    inputs = Collections.synchronizedMap(new WeakHashMap<>());
    rxOutput = PublishSubject.create();
    rxValue = ReplaySubject.createWithSize(EVALUATION_HISTORY);
  }

  @RequiredArgsConstructor
  @ToString
  @EqualsAndHashCode
  public static class Evaluation {
    public final long time;
    public final float value;
  }

  @RequiredArgsConstructor
  @ToString
  @EqualsAndHashCode
  public static class ContextualEvaluation {
    public final Context context;
    public final Evaluation evaluation;
  }

  public static final int EVALUATION_HISTORY = 10;

  public Observable<ContextualEvaluation> rxValue() {
    return rxValue;
  }

  /**
   * Emits an activation signal or schedules a re-evaluation at a future time,
   * depending on current state.
   */
  private Observable<Long> evaluate(final Context context, final long time) {
    final float value = getValue(context, time);
    rxValue.onNext(new ContextualEvaluation(context, new Evaluation(time, value)));

    if (value >= THRESHOLD) {
      return Observable.just(time);
    } else {
      final long nextCrit = getNextCriticalPoint(context, time);
      if (nextCrit == Long.MAX_VALUE) {
        return Observable.empty();
      } else {
        return Observable.timer(nextCrit - time, TimeUnit.MILLISECONDS).flatMap(x -> evaluate(context, nextCrit));
      }
    }
  }

  public float getValue(final Context context, final long time) {
    float value = 0;
    synchronized (inputs) {
      for (final Profile profile : inputs.values()) {
        value += profile.getValue(context, time);
      }
    }
    return value;
  }

  /**
   * Gets the next time the synapse should be evaluated if current conditions
   * hold. This is the minimum of the next time the synapse would cross the
   * activation threshold given current conditions, and the zeros of the
   * activations involved. Activations that have already fully decayed do not
   * affect this calculation.
   */
  private long getNextCriticalPoint(final Context context, final long time) {
    float totalValue = 0, totalDecayRate = 0;
    long nextZero = Long.MAX_VALUE;
    synchronized (inputs) {
      for (final Profile profile : inputs.values()) {
        final float value = profile.getValue(context, time);
        if (value != 0) {
          totalValue += value;
          totalDecayRate += profile.getLastCoefficient(context) / profile.decayPeriod;
          nextZero = Math.min(nextZero, profile.getZero(context));
        }
      }
    }
    final long untilThresh = (long) ((1 - totalValue) / -totalDecayRate);
    return untilThresh <= 0 ? nextZero : Math.min(untilThresh + time, nextZero);
  }

  public Observable<Node.Activation> rxActivate() {
    return rxOutput;
  }

  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.defaultWriteObject();
    synchronized (inputs) {
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
      final Profile profile = newProfile(node);
      profile.coefficient = (Distribution) o.readObject();
      profile.decayPeriod = o.readLong();
      profile.updateSubscription();
      inputs.put(node, profile);
    }
  }

  private Profile newProfile(final Node source) {
    return new Profile(source);
  }

  public Synapse setCoefficient(final Node node, final float coefficient) {
    final Profile activation = inputs.computeIfAbsent(node, this::newProfile);
    activation.coefficient.clear();
    activation.coefficient.add(coefficient, 1);
    activation.updateSubscription();
    return this;
  }

  public float getCoefficient(final Node node) {
    final Profile activation = inputs.get(node);
    return activation == null ? 0 : activation.coefficient.getMode();
  }

  /**
   * @param node      the input node
   * @param decayRate the linear signal decay period, in milliseconds from
   *                  activation to 0
   */
  public Synapse setDecayPeriod(final Node node, final long decayPeriod) {
    inputs.computeIfAbsent(node, this::newProfile).decayPeriod = decayPeriod;
    return this;
  }

  public long getDecayPeriod(final Node node) {
    final Profile activation = inputs.get(node);
    return activation == null ? 0 : activation.decayPeriod;
  }

  public void dissociate(final Node node) {
    final Profile profile = inputs.remove(node);
    if (profile != null) {
      profile.subscription.dispose();
    }
  }
}
