package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.BiConsumer;

import lombok.val;

public class Cluster<T extends Node> implements Serializable {
  private static final long serialVersionUID = 1L;

  private transient RecencyQueue<WeakReference<T>> activations = new RecencyQueue<>();

  // TODO: register cleanup task

  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.defaultWriteObject();
    val nodes = new ArrayList<T>();
    for (val ref : activations) {
      final T node = ref.get();
      if (node != null) {
        nodes.add(node);
      }
    }
    o.writeObject(nodes);
  }

  private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
    o.defaultReadObject();

    activations = new RecencyQueue<>();
    // Nodes will be re-added as they activate.
    o.readObject();
  }

  protected class Link implements Serializable {
    private static final long serialVersionUID = 1L;

    private transient RecencyQueue<WeakReference<T>>.Link link;

    public Link(final T node) {
      link = activations.new Link(new WeakReference<>(node));
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
      o.defaultWriteObject();
      o.writeObject(link.get()
          .get());
    }

    @SuppressWarnings("unchecked")
    private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
      o.defaultReadObject();
      link = activations.new Link(new WeakReference<>((T) o.readObject()));
    }

    /**
     * Promotes the node within the recency queue. This should be called at the
     * beginning of the node activation to ensure that any side effects that query
     * the recency queue receive a sequence consistent with the updated activation
     * timestamps.
     */
    public void promote() {
      link.promote();
    }
  }

  public void clean() {
    // TODO: reverse this iterator (LRU heuristic)
    val it = activations.iterator();
    while (it.hasNext()) {
      if (it.next().get() == null) {
        it.remove();
      }
    }
  }

  public Iterable<T> activations() {
    return () -> new Iterator<T>() {
      final Iterator<WeakReference<T>> it = activations.iterator();
      T next;

      {
        advance();
      }

      private void advance() {
        while (it.hasNext()) {
          next = it.next()
              .get();
          if (next == null) {
            it.remove();
          } else {
            return;
          }
        }

        next = null;
      }

      @Override
      public boolean hasNext() {
        return next != null;
      }

      @Override
      public T next() {
        val next = this.next;
        advance();
        return next;
      }
    };
  }

  public static <T extends Node> void forEachByTrace(final Cluster<T> cluster, final IntegrationProfile profile,
      final long t, final BiConsumer<T, Float> action) {
    final long horizon = t - profile.period();

    for (final T node : cluster.activations()) {
      if (node.getLastActivation().get() <= horizon) {
        break;
      }

      final float trace = node.getTrace().evaluate(t, profile);
      if (trace > 0) {
        action.accept(node, trace);
      }
    }
  }

  public static void associate(final Cluster<? extends Prior> priorCluster, final Posterior posterior,
      final Iterable<IntegrationProfile> profiles, final long t, final float weight) {
    val conjunction = new ConjunctionJunction();
    for (val profile : profiles) {
      forEachByTrace(priorCluster, profile, t, (node, trace) -> conjunction.add(node, profile, trace));
    }
    conjunction.build(posterior, weight);
  }

  /**
   * Forms explicit conjunctive associations between the given prior cluster and
   * posterior cluster, using the given integration profile for the new edges. The
   * active posteriors considered at the time this function executes are always
   * based on a {@link IntegrationProfile#TRANSIENT} window.
   */
  public static void associate(final Cluster<? extends Prior> priorCluster,
      final Cluster<? extends Posterior> posteriorCluster, final Iterable<IntegrationProfile> profiles) {
    forEachByTrace(posteriorCluster, IntegrationProfile.TRANSIENT, Scheduler.global.now(),
        (posterior, posteriorTrace) -> {
          // For each posterior, connect all priors with timings that could have
          // contributed to the firing in a conjunctive way.

          associate(priorCluster, posterior, profiles, posterior.getLastActivation().get(), posteriorTrace);
        });
  }

  public static void associate(final Cluster<? extends Prior> priorCluster,
      final Cluster<? extends Posterior> posteriorCluster) {
    associate(priorCluster, posteriorCluster, IntegrationProfile.COMMON);
  }

  /**
   * Breaks associations between the given prior cluster and posterior cluster.
   * The active posteriors considered at the time this function executes are
   * always based on a {@link IntegrationProfile#TRANSIENT} window. The degree to
   * which associations are broken are determined by this window and the prior
   * trace.
   */
  public static void disassociate(final Cluster<? extends Prior> priorCluster,
      final Cluster<? extends Posterior> posteriorCluster) {
    forEachByTrace(posteriorCluster, IntegrationProfile.TRANSIENT, Scheduler.global.now(),
        (posterior, posteriorTrace) -> {
          // For each posterior, find all priors in the designated cluster and reduce
          // their weight by the product of the pertinent traces.

          for (val prior : posterior.getPriors()) {
            if (prior.node().getCluster() == priorCluster) {
              final float priorTrace = prior.node().getTrace()
                  .evaluate(posterior.getLastActivation().get(), prior.profile());
              if (priorTrace > 0) {
                prior.distribution().reinforce(-prior.distribution().getWeight() * priorTrace * posteriorTrace);
              }
            }
          }
        });
  }
}
