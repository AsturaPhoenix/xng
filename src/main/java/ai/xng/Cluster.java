package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;

import lombok.val;

public class Cluster<T extends Node> implements Serializable {
  private static final long serialVersionUID = 1L;

  protected transient RecencyQueue<WeakReference<T>> activations = new RecencyQueue<>();

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
    // Nodes will be added as their links deserialize.
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

    public void promote() {
      link.promote();
    }
  }

  public void clean() {
    val it = activations.iterator();
    while (it.hasNext()) {
      if (it.next()
          .get() == null) {
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

  /**
   * Forms explicit conjunctive associations between the given prior cluster and
   * posterior cluster, using the given integration profile for the new edges. The
   * active posteriors considered at the time this function executes are always
   * based on a {@link IntegrationProfile#TRANSIENT} window.
   */
  public static void associate(final Cluster<? extends Prior> priorCluster,
      final Cluster<? extends Posterior> posteriorCluster, final IntegrationProfile profile) {
    final long now = Scheduler.global.now();

    for (final Posterior posterior : posteriorCluster.activations()) {
      // The recency queue will only iterate over nodes that have been activated at
      // some point.
      final long t1 = posterior.getLastActivation().get();

      // Use the transient profile to drive association strength.
      if (t1 <= now - IntegrationProfile.TRANSIENT.period()) {
        break;
      }

      final float posteriorTrace = posterior.getTrace().evaluate(now, IntegrationProfile.TRANSIENT);

      if (posteriorTrace == 0) {
        continue;
      }
      assert posteriorTrace > 0;

      // For each posterior, connect all priors with timings that could have
      // contributed to the firing in a conjunctive way.

      val conjunction = new ConjunctionJunction(t1, profile);

      for (final Prior prior : priorCluster.activations()) {
        if (prior.getLastActivation().get() <= t1 - profile.period()) {
          break;
        }

        conjunction.add(prior);
      }

      conjunction.build(posterior, posteriorTrace * Distribution.DEFAULT_WEIGHT);
    }
  }
}
