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
import java.util.Map.Entry;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.Getter;
import lombok.val;

/**
 * Represents the incoming logical junction of input node signals towards a
 * specific output node. This implements a simplified, linear form of leaky
 * integrate and fire.
 * <p>
 * Minimal effort is made to preserve activation across serialization boundaries
 * since behavior while the system is down is discontinuous anyway. In the
 * future, it is likely that either we will switch to relative time and fully
 * support serialization or else completely clear activation on deserialization.
 * <p>
 * For thread safety, this class uses its intrinsic lock so that
 * {@link SynapticNode} can hold it while splitting nodes.
 */
public class Synapse implements Serializable {
  private static final long serialVersionUID = 1779165354354490167L;

  public static final float THRESHOLD = 1;
  public static final float REINFORCEMENT_MARGIN = .2f;
  public static final float AUTOREINFORCEMENT = .2f;
  public static final long DEFAULT_DECAY = 1000;
  public static final float HEBBIAN_WEIGHT_FACTOR = .45f;
  // Evaluation history TTL, in ms.
  public static final long EVALUATION_HISTORY = 15000;

  public class ContextualState {
    private final Context context;
    private final Subject<Observable<Long>> rxEvaluations;
    private final Map<Node, ArrayDeque<Evaluation>> evaluations = new WeakHashMap<>();

    public ContextualState(final Context context) {
      this.context = context;
      rxEvaluations = PublishSubject.create();
      Observable.switchOnNext(rxEvaluations).subscribe(t ->
      // (We should be locking Synchronized.this at this point.)
      node.activate(context));
    }

    public CompletableFuture<Void> reinforce(Optional<Long> decayPeriod, float weight) {
      val future = new CompletableFuture<Void>();

      context.getScheduler().ensureOnThread(() -> {
        synchronized (Synapse.this) {
          final long now = context.getScheduler().now(TimeUnit.MILLISECONDS);

          for (final Entry<Node, ArrayDeque<Evaluation>> entry : evaluations.entrySet()) {
            final Profile profile = inputs.get(entry.getKey());

            for (final Evaluation evaluation : entry.getValue()) {
              final long t = evaluation.time;
              float wt = weight;
              if (decayPeriod.isPresent()) {
                long t0 = now - decayPeriod.get();
                if (t <= t0)
                  continue;
                wt *= (float) (t - t0) / decayPeriod.get();
              }

              val distribution = profile.coefficient;
              distribution.add(evaluation.value, wt);

              // Hebbian element

              final float wh = HEBBIAN_WEIGHT_FACTOR * wt;

              final Optional<Long> followingActivation = node.getRecentActivations(context, true)
                  .dropWhile(ta -> ta < t).takeWhile(ta -> ta < t + 2 * profile.decayPeriod).findFirst();

              // correlation between prior and posterior
              // This may be invalid if the synapse was moved to a different node; no effort
              // is made to invalidate contextual states after such an operation.
              final float correlation = followingActivation.map(ta -> 1 - 2 * (float) (ta - t) / profile.decayPeriod)
                  .orElse(-1f);

              float effectiveTotal = evaluation.total.value;
              if (followingActivation.isPresent()) {
                // We could analyze over any subsequent activations or evaluations within the
                // window to find a maximum, but the first peak should be sufficient to get us
                // moving in the right direction.
                final float activationValue = Synapse.this.getValue(context, followingActivation.get()).value;
                effectiveTotal = Math.max(effectiveTotal, activationValue);
              }

              distribution.add(
                  evaluation.value
                      + Math.max(THRESHOLD + REINFORCEMENT_MARGIN - effectiveTotal, 0) / evaluation.total.weight,
                  wh * correlation);
              distribution.add(
                  evaluation.value
                      + Math.min(THRESHOLD - REINFORCEMENT_MARGIN - effectiveTotal, 0) / evaluation.total.weight,
                  -wh * correlation);
            }
          }
        }
        future.complete(null);
      });

      return future;
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
      coefficient = new UnimodalHypothesis(0);
      this.incoming = new WeakReference<>(incoming);
      decayPeriod = DEFAULT_DECAY;
      subscription = incoming.rxActivate().subscribe(this::onActivate);
    }

    private void onActivate(final Node.Activation activation) {
      val ref = activation.context.new Ref();

      // Although we should already be on the context thread by now, defer evaluation
      // until peer node activation timestamps have had a chance to catch up.
      //
      // Notably we defer here rather than on the Node side because, for example,
      // rxActivate also drives reinforcement, which requires consistency. e.g. there
      // was a case where posteriors looked like priors because they were already
      // present in the activation history and then had their timestamp updated before
      // the call to update the reinforcement was triggered from the prior, which was
      // then the "most recent" activation.

      activation.context.getScheduler().scheduleDirect(() -> {
        final ContextualState state = activation.context.synapseState(Synapse.this);
        synchronized (Synapse.this) {
          final ExtrapolatedEvaluation totalBefore = Synapse.this.getValue(activation.context, activation.timestamp);
          final ExtrapolatedEvaluation localBefore = getValue(activation.context, activation.timestamp);
          final float localAfter = coefficient.generate();
          assert Float.isFinite(localAfter) : String.format("%s (%s)", localAfter, coefficient);
          final float totalAfter = totalBefore.value - localBefore.value + localAfter;

          coefficient.add(localAfter, AUTOREINFORCEMENT);

          val evaluations = state.evaluations.computeIfAbsent(incoming.get(), node -> new ArrayDeque<>());
          final long oldestAllowed = activation.context.getScheduler().now(TimeUnit.MILLISECONDS) - EVALUATION_HISTORY;
          while (!evaluations.isEmpty() && evaluations.getLast().time < oldestAllowed) {
            evaluations.removeLast();
          }
          evaluations.addFirst(new Evaluation(activation.timestamp, localAfter,
              new ExtrapolatedEvaluation(totalAfter, totalBefore.weight - localBefore.weight + 1)));
          state.rxEvaluations
              .onNext(processTransition(activation.context, activation.timestamp, totalBefore.value, totalAfter)
                  .doFinally(ref::close));
        }
      });
    }

    public ExtrapolatedEvaluation getValue(final Context context, final long time) {
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
      return lastEvaluation == null ? ExtrapolatedEvaluation.ZERO : extrapolateEvaluation(lastEvaluation, time);
    }

    public ExtrapolatedEvaluation extrapolateEvaluation(final Evaluation evaluation, final long time) {
      final long dt = time - evaluation.time;
      return dt < decayPeriod ? ExtrapolatedEvaluation.extrapolate(evaluation.value, 1 - (float) dt / decayPeriod)
          : ExtrapolatedEvaluation.ZERO;
    }
  }

  @Getter
  private Node node;
  private transient Map<Node, Profile> inputs;

  public Synapse(final SynapticNode node) {
    this.node = node;
    init();
  }

  private void init() {
    inputs = new WeakHashMap<>();
  }

  public synchronized void setNode(final Node node) {
    if (this.node instanceof SynapticNode oldNode && oldNode.getSynapse() == this) {
      throw new IllegalStateException(
          "Cannot move synapse bound to SynapticNode. Call appropriate methods on SynapticNode instead.");
    }
    if (node instanceof SynapticNode newNode && newNode.getSynapse() != this) {
      throw new IllegalArgumentException(
          "Cannot move synapse to SynapticNode directly. Call appropriate methods on SynapticNode instead.");
    }

    this.node = node;
  }

  public static record Evaluation(long time, float value, ExtrapolatedEvaluation total) {
  }

  /**
   * An evaluation in the context of integration. For an individual profile, the
   * weight is the time decay at the requested time. For the synapse, it is the
   * sum of these weights from all profiles.
   */
  public static record ExtrapolatedEvaluation(float value, float weight) {
    public static final ExtrapolatedEvaluation ZERO = new ExtrapolatedEvaluation(0, 0);

    public static ExtrapolatedEvaluation extrapolate(final float v0, final float weight) {
      return new ExtrapolatedEvaluation(v0 * weight, weight);
    }
  }

  /**
   * Emits an activation signal or schedules a re-evaluation at a future time,
   * depending on current state.
   */
  private Observable<Long> processTransition(final Context context, final long time, final float before,
      final float after) {
    if (after >= THRESHOLD) {
      // Only signal on transitions.
      return before < THRESHOLD ? Observable.just(time) : Observable.empty();
    } else {
      final long nextThreshold = getNextThreshold(context, time);
      if (nextThreshold == Long.MAX_VALUE) {
        return Observable.empty();
      } else {
        return Observable.timer(nextThreshold - time, TimeUnit.MILLISECONDS).map(z -> nextThreshold);
      }
    }
  }

  public synchronized ExtrapolatedEvaluation getValue(final Context context, final long time) {
    assert context.getScheduler().isOnThread();
    return inputs.values().stream().map(profile -> profile.getValue(context, time)).reduce(ExtrapolatedEvaluation.ZERO,
        (a, b) -> new ExtrapolatedEvaluation(a.value + b.value, a.weight + b.weight));
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
      assert decayProfile.evaluation.time <= time;

      decayProfile.expiration = decayProfile.evaluation.time + profile.decayPeriod;
      if (time >= decayProfile.expiration)
        continue;

      decayProfile.decayRate = decayProfile.evaluation.value / profile.decayPeriod;
      decayProfiles.add(decayProfile);

      totalValue += profile.extrapolateEvaluation(decayProfile.evaluation, time).value;
      totalDecayRate += decayProfile.decayRate;
    }

    if (totalValue >= THRESHOLD) {
      // floating point roundoff edge case
      return time;
    }

    // Descend by expiration; we'll pop from the back as evaluations expire.
    decayProfiles.sort((a, b) -> Long.compare(b.expiration, a.expiration));

    while (!decayProfiles.isEmpty()) {
      assert totalValue < THRESHOLD : totalValue;

      val expiringProfile = decayProfiles.remove(decayProfiles.size() - 1);

      if (totalDecayRate < 0) {
        final long nextThresh = time + (long) Math.ceil((THRESHOLD - totalValue) / -totalDecayRate);
        if (nextThresh <= expiringProfile.expiration)
          return nextThresh;
      }

      totalValue -= (expiringProfile.expiration - time) * totalDecayRate;
      if (totalValue >= THRESHOLD) {
        // floating point roundoff edge case
        return expiringProfile.expiration;
      }

      time = expiringProfile.expiration;
      totalDecayRate -= expiringProfile.decayRate;
    }
    return Long.MAX_VALUE;
  }

  private synchronized void writeObject(final ObjectOutputStream o) throws IOException {
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

  public synchronized Profile profile(final Node node) {
    return inputs.computeIfAbsent(node, Profile::new);
  }

  public Synapse setCoefficient(final Node node, final float coefficient) {
    val profile = profile(node);
    profile.coefficient.set(coefficient);
    return this;
  }

  public synchronized float getCoefficient(final Node node) {
    final Profile profile = inputs.get(node);
    return profile == null ? 0 : profile.coefficient.getMode();
  }

  public synchronized void replacePrior(final Node oldPrior, final Node newPrior) {
    setCoefficient(newPrior, getCoefficient(oldPrior));
    dissociate(oldPrior);
  }

  /**
   * @param node      the input node
   * @param decayRate the linear signal decay period, in milliseconds from
   *                  activation to 0
   */
  public Synapse setDecayPeriod(final Node node, final long decayPeriod) {
    if (decayPeriod <= 0) {
      // This condition simplifies the math and promotes timing tolerance.
      throw new IllegalArgumentException("Decay period must be positive.");
    }
    profile(node).decayPeriod = decayPeriod;
    return this;
  }

  public synchronized long getDecayPeriod(final Node node) {
    final Profile profile = inputs.get(node);
    return profile == null ? 0 : profile.decayPeriod;
  }

  public synchronized void dissociate(final Node node) {
    final Profile profile = inputs.remove(node);
    if (profile != null) {
      profile.subscription.dispose();
    }
  }

  public Evaluation getLastEvaluation(final Context context, final Node incoming) {
    return getPrecedingEvaluation(context, incoming, Long.MAX_VALUE);
  }

  public synchronized Evaluation getPrecedingEvaluation(final Context context, final Node incoming, final long time) {
    final ArrayDeque<Evaluation> evaluations = context.synapseState(this).evaluations.get(incoming);
    if (evaluations == null)
      return null;

    return evaluations.stream().dropWhile(e -> e.time > time).findFirst().orElse(null);
  }

  public synchronized Map<Node, List<Evaluation>> getRecentEvaluations(final Context context, final long since) {
    val evaluations = context.synapseState(this).evaluations;

    final Map<Node, List<Evaluation>> recent = new HashMap<>();
    for (val entry : evaluations.entrySet()) {
      recent.put(entry.getKey(),
          entry.getValue().stream().takeWhile(e -> e.time >= since).collect(Collectors.toList()));
    }
    return recent;
  }

  @Override
  public synchronized String toString() {
    return inputs.entrySet().stream()
        .map(entry -> String.format("%s: %s", entry.getKey(), entry.getValue().getCoefficient()))
        .collect(Collectors.joining("\n"));
  }
}
