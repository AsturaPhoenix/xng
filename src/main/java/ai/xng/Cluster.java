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
    return new Iterable<T>() {
      public Iterator<T> iterator() {
        val it = activations.iterator();
        return new Iterator<T>() {
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
                break;
              }
            }
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
    };
  }

  private static record WeightedPrior(Prior prior, float weight) {
  }

  public static void associate(final Cluster<? extends Prior> priorCluster,
      final Cluster<? extends Posterior> posteriorCluster) {
    final long now = Scheduler.global.now();

    for (final Posterior posterior : posteriorCluster.activations()) {
      // For each posterior, connect all priors with timings that could have
      // contributed to the firing in a conjunctive way.

      if (posterior.getLastActivation()
          .map(t -> t <= now - (Prior.RAMP_UP + Prior.RAMP_DOWN))
          .orElse(true)) {
        break;
      }

      final long t1 = posterior.getLastActivation()
          .get();

      float priorWeightTotal = 0;
      val priors = new ArrayList<WeightedPrior>();

      for (final Prior prior : priorCluster.activations()) {
        if (prior.getLastActivation()
            .map(t -> t <= t1 - (Prior.RAMP_UP + Prior.RAMP_DOWN))
            .orElse(true)) {
          break;
        }

        final long dt = t1 - prior.getLastActivation()
            .get();
        if (dt <= 0) {
          continue;
        }

        final float priorWeight = dt < Prior.RAMP_UP ? (float) dt / Prior.RAMP_UP
            : 1 - (float) (dt - Prior.RAMP_UP) / Prior.RAMP_DOWN;
        priorWeightTotal += priorWeight;
        priors.add(new WeightedPrior(prior, priorWeight));
      }
    }
  }
}
