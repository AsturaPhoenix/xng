package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;

import ai.xng.constructs.CoincidentEffect;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.val;

public abstract class Cluster<T extends Node> implements Serializable {
  private final WeakSerializableRecencyQueue<T> activations = new WeakSerializableRecencyQueue<>();
  private transient Subject<T> rxActivations;

  public Observable<T> rxActivations() {
    return rxActivations;
  }

  public Cluster() {
    init();
  }

  // TODO: register cleanup task

  private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
    o.defaultReadObject();
    init();
  }

  private void init() {
    rxActivations = PublishSubject.create();
  }

  protected class ClusterNodeTrait implements Serializable {
    private final WeakSerializableRecencyQueue<T>.Link link;

    public ClusterNodeTrait(final T owner) {
      link = activations.new Link(owner);
    }

    /**
     * Promotes the node within the recency queue and publishes it to the
     * Observable. This should be called at the beginning of the node activation to
     * ensure that any side effects that query the recency queue receive a sequence
     * consistent with the updated activation timestamps.
     */
    public void activate() {
      link.promote();
      // Defer publishing the update until other node updates (like last activation
      // time) have been processed.
      // TODO: This seems like it could be done better. If this is made more robust,
      // also revisit the race condition mitigations in CoincidentEffect.
      Scheduler.global.postTask(() -> rxActivations.onNext(link.get()));
    }
  }

  public void clean() {
    activations.clean();
  }

  public Iterable<T> activations() {
    return activations::iterator;
  }

  public static <T extends Node> void forEachByTrace(final Cluster<? extends T> cluster,
      final IntegrationProfile profile, final long t, final BiConsumer<T, Float> action) {
    final long horizon = t - profile.period();

    for (final T node : cluster.activations) {
      if (node.getLastActivation().get() <= horizon) {
        break;
      }

      final float trace = node.getTrace().evaluate(t, profile);
      if (trace > 0) {
        action.accept(node, trace);
      }
    }
  }

  public static record PriorClusterProfile(Cluster<? extends Prior> cluster,
      ImmutableList<IntegrationProfile> profiles) {

    public PriorClusterProfile(final Cluster<? extends Prior> cluster, final IntegrationProfile... profiles) {
      this(cluster, ImmutableList.copyOf(profiles));
    }

    public static class ListBuilder {
      private final ImmutableList.Builder<PriorClusterProfile> backing = ImmutableList.builder();
      private ImmutableList<IntegrationProfile> baseProfiles = ImmutableList.of(IntegrationProfile.TRANSIENT);

      public ListBuilder baseProfiles(final IntegrationProfile... profiles) {
        baseProfiles = ImmutableList.copyOf(profiles);
        return this;
      }

      public ListBuilder add(final Cluster<? extends Prior> cluster, final IntegrationProfile... additionalProfiles) {
        backing.add(
            new PriorClusterProfile(cluster,
                ImmutableList.<IntegrationProfile>builder()
                    .addAll(baseProfiles)
                    .addAll(Arrays.asList(additionalProfiles))
                    .build()));
        return this;
      }

      public ImmutableList<PriorClusterProfile> build() {
        return backing.build();
      }
    }
  }

  public static abstract class CaptureBuilder {
    private final PriorClusterProfile.ListBuilder priors = new PriorClusterProfile.ListBuilder();

    public CaptureBuilder baseProfiles(final IntegrationProfile... profiles) {
      priors.baseProfiles(profiles);
      return this;
    }

    public CaptureBuilder priors(final Cluster<? extends Prior> cluster,
        final IntegrationProfile... additionalProfiles) {
      priors.add(cluster, additionalProfiles);
      return this;
    }

    public ActionCluster.Node posteriors(final PosteriorCluster<?> posteriorCluster) {
      return posteriors(posteriorCluster, 1);
    }

    public ActionCluster.Node posteriors(final PosteriorCluster<?> posteriorCluster, final float weight) {
      return capture(priors.build(), posteriorCluster, weight);
    }

    protected abstract ActionCluster.Node capture(Iterable<PriorClusterProfile> priors,
        PosteriorCluster<?> posteriorCluster, float weight);
  }

  /**
   * Captures a posterior activation state to be reproduced by activations in the
   * given prior clusters, using the given integration profiles. Posteriors are
   * captured by coincidence and priors are captured by trace.
   * <p>
   * To respect firing order, the traces for priors are evaluated against the
   * firing time of each posterior. However, to ensure that the expected
   * disassociation happens for edges not exercised during the capture, this
   * occurs over the set of priors captured during this operation rather than the
   * set of priors with nonzero traces at the time of posterior activation.
   * Notably this means that priors whose traces have decayed by the time the
   * capture takes place are ignored even if they did have nonzero traces at the
   * time of posterior activation. This should not affect common usage.
   * <p>
   * Furthermore to allow for use in property binding, where conjunctions need to
   * be captured with priors in common with other conjunctions captured at
   * different times, we must not disassociate edges that are not expected to
   * result in an activation. An example is capturing AB -> C but later AD -> E.
   * Naively, the second capture would observe that since A activated but C did
   * not, the A-C edge should disassociate; this is not the desired behavior.
   * <p>
   * For convenience in the common case, disassociation is not limited to the
   * selected cluster. Any expected posterior of the selected priors that was not
   * activated during the capture is disassociated.
   * <p>
   * More sophisticated capture behaviors are conceivable but requirements easily
   * become self inconsistent.
   */
  public static class Capture extends CoincidentEffect<Posterior> {
    private static class ExpectedPosterior {
      final BakingIntegrator subsetIntegrator = new BakingIntegrator();
      final Collection<ConjunctionJunction.Component> contributors = new ArrayList<>();
    }

    private final Iterable<PriorClusterProfile> priors;
    private final float weight;

    private transient ConjunctionJunction capturedPriors;
    private transient Map<Posterior, ExpectedPosterior> wantedPosteriors;

    /**
     * @param weight The weight to use for {@link Distribution#set(float, float)}
     *               operations. A weight of 0 turns this into a disassociate
     *               operation. Weight is not used when disassociating missing
     *               expected posteriors.
     */
    public Capture(final ActionCluster actionCluster, final Iterable<PriorClusterProfile> priors,
        final PosteriorCluster<?> posteriorCluster, final float weight) {
      super(actionCluster, posteriorCluster);
      this.priors = priors;
      this.weight = weight;
    }

    @Override
    protected void onActivate() {
      capturePriors();
      scheduleDeactivationCheck();
      super.onActivate();
    }

    private void capturePriors() {
      final long t = Scheduler.global.now();
      capturedPriors = new ConjunctionJunction();
      wantedPosteriors = new HashMap<>();

      for (val prior : priors) {
        for (val profile : prior.profiles) {
          forEachByTrace(prior.cluster, profile, t, (node, trace) -> {
            capturedPriors.add(node, profile, trace);
            val contributor = new ConjunctionJunction.Component(node, profile, trace);
            for (val posterior : node.getPosteriors()) {
              // The posteriors considered for disassocation are not limited to the selected
              // posterior cluster, for convenience in the common usage.

              // If the posterior is currently active, of course we won't disassociate it.
              if (posterior.node().getIntegrator().isActive()) {
                continue;
              }

              final ExpectedPosterior expectedPosterior = wantedPosteriors.computeIfAbsent(posterior.node(),
                  __ -> new ExpectedPosterior());

              // This includes only pulses from the actual prior and not suppressions of those
              // pulses via suppressPosteriors. This allows suppressPosteriors to be used to
              // disassociate posteriors that would otherwise have fired.
              for (val spike : posterior.edge().spikes()) {
                expectedPosterior.subsetIntegrator.add(spike.rampUp);
                expectedPosterior.subsetIntegrator.add(spike.rampDown);
              }

              expectedPosterior.contributors.add(contributor);
            }
          });
        }
      }
    }

    // TODO: This can misbehave in cases where the integrator is reset via
    // resetPosteriors.
    private void scheduleDeactivationCheck() {
      final Optional<Long> deactivation = node.getIntegrator().nextThreshold(Scheduler.global.now(), -1);
      if (deactivation.isPresent()) {
        Scheduler.global.postTask(() -> {
          if (node.getIntegrator().isActive()) {
            scheduleDeactivationCheck();
          } else {
            endCapture();
          }
        }, deactivation.get());
      } else {
        endCapture();
      }
    }

    private static Iterator<Long> upperBound(final Iterator<Long> source, final long limit) {
      return new Iterator<Long>() {
        boolean truncate;

        @Override
        public boolean hasNext() {
          return source.hasNext() && !truncate;
        }

        @Override
        public Long next() {
          final long next = source.next();
          if (next >= limit) {
            truncate = true;
            return limit;
          }
          return next;
        }
      };
    }

    /**
     * Disassociates any expected posterior cluster nodes that were not active
     * during the capture.
     */
    private void endCapture() {
      final long captureStart = node.getLastActivation().get(), now = Scheduler.global.now();

      for (val posterior : wantedPosteriors.entrySet()) {
        if (posterior.getKey().getLastActivation().map(t -> t >= captureStart).orElse(false)) {
          continue;
        }

        val subsetIntegrator = posterior.getValue().subsetIntegrator;
        if (Iterators.any(upperBound(Stream
            .iterate(Optional.of(captureStart), t -> subsetIntegrator.nextCriticalPoint(t.get()))
            .takeWhile(Optional::isPresent).map(Optional::get).iterator(), now),
            t -> subsetIntegrator.evaluate(t).value() >= ThresholdIntegrator.THRESHOLD)) {

          for (val contributor : posterior.getValue().contributors) {
            val distribution = contributor.node().getPosteriors().getEdge(posterior.getKey(),
                contributor.profile()).distribution;
            distribution.reinforce(weightByTrace(-distribution.getWeight(), 0, contributor.weight()));
          }
        }
      }

      capturedPriors = null;
      wantedPosteriors = null;
    }

    @Override
    protected void apply(final Posterior posterior) {
      // Re-evaluate prior traces to respect firing order.
      final long t = posterior.getLastActivation().get();
      val timeSensitiveConjunction = new ConjunctionJunction();
      for (val component : capturedPriors) {
        timeSensitiveConjunction.add(component.node(), component.profile(),
            component.node().getTrace().evaluate(t, component.profile()));
      }
      timeSensitiveConjunction.build(posterior, (distribution, coefficient) -> distribution.set(coefficient, weight));
      wantedPosteriors.remove(posterior);
    }
  }

  public static ActionCluster.Node suppressPosteriors(final ActionCluster actionCluster, final BiCluster priorCluster) {
    return new CoincidentEffect.Lambda<>(actionCluster, priorCluster, node -> {
      for (final Connections.Entry<Posterior> entry : node.getPosteriors()) {
        entry.edge().suppress(1);
      }
    }).node;
  }

  private static float weightByTrace(final float value, final float identity, final float trace) {
    final float tolerantTrace = Math.min(1, trace * (1 + Prior.THRESHOLD_MARGIN));
    return identity + (value - identity) * tolerantTrace;
  }

  /**
   * Scales posteriors of nodes in a prior cluster by prior cluster trace.
   */
  public static void scalePosteriors(final Cluster<? extends Prior> priorCluster, final float factor) {
    forEachByTrace(priorCluster, IntegrationProfile.TRANSIENT, Scheduler.global.now(),
        (prior, trace) -> {
          for (val entry : prior.getPosteriors()) {
            entry.edge().distribution.scale(weightByTrace(factor, 1, trace));
          }
        });
  }
}
