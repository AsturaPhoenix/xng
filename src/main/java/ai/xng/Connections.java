package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.collect.Multimaps;

import ai.xng.ThresholdIntegrator.Spike;
import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.experimental.UtilityClass;

@UtilityClass
public class Connections {
  public static class Edge implements Serializable {
    public final Distribution distribution;
    private final Posterior posterior;
    public final IntegrationProfile profile;

    private transient List<Spike> activations;
    private transient List<Suppression> suppressions;

    @RequiredArgsConstructor
    private static class Suppression {
      final float factor;
      final Spike spike;
    }

    private Edge(final Posterior posterior, final IntegrationProfile profile) {
      init();
      distribution = new UnimodalHypothesis() {
        @Override
        public void set(float value, float weight) {
          super.set(value, weight);
          invalidate();
        }

        @Override
        public void add(float value, float weight) {
          super.add(value, weight);
          invalidate();
        }

        @Override
        public void scale(float factor) {
          super.scale(factor);
          invalidate();
        }
      };
      this.posterior = posterior;
      this.profile = profile;
    }

    private void init() {
      activations = new ArrayList<>();
      suppressions = new ArrayList<>();
    }

    private void evict() {
      val now = Scheduler.global.now();
      activations.removeIf(spike -> spike.end() <= now);
      suppressions.removeIf(suppression -> suppression.spike.end() <= now);
    }

    private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
      init();
      o.defaultReadObject();
    }

    private void invalidate() {
      if (activations.isEmpty() && suppressions.isEmpty()) {
        return;
      }

      evict();
      final float newRate = distribution.getMode() / profile.rampUp();
      // Since these are all adjustments to the same integrator, we could actually
      // defer the invalidation, but we expect the size of this loop to be 1 so it's
      // premature optimization.
      for (val spike : activations) {
        spike.adjustRampUp(newRate);
      }
      for (val suppression : suppressions) {
        suppression.spike.adjustRampUp(-suppression.factor * newRate);
      }
    }

    public void activate() {
      activations.add(posterior.getIntegrator().add(profile, distribution.generate()));
    }

    public void suppress(final float factor) {
      suppressions.add(new Suppression(factor,
          posterior.getIntegrator().add(profile, -factor * distribution.generate())));
    }
  }

  public static record Entry<T> (T node, Edge edge) {
  }

  private static record Key<T extends Serializable> (T node, IntegrationProfile profile) implements Serializable {
  }

  private static class WeakPrior implements Serializable {
    transient int hashCode;
    transient WeakReference<Prior> ref;

    WeakPrior(final Prior prior) {
      this.hashCode = prior.hashCode();
      ref = new WeakReference<>(prior);
    }

    private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
      o.defaultReadObject();
      val prior = (Prior) o.readObject();
      hashCode = prior == null ? 0 : prior.hashCode();
      ref = new WeakReference<>(prior);
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
      o.defaultWriteObject();
      o.writeObject(ref.get());
    }

    @Override
    public boolean equals(final Object obj) {
      if (obj == this) {
        return true;
      }
      val prior = ref.get();
      if (prior == null) {
        return false;
      }
      return obj instanceof WeakPrior wp && prior == wp.ref.get();
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public String toString() {
      return Objects.toString(ref.get());
    }
  }

  private static <T> String toString(final Iterable<Entry<T>> connections) {
    val sb = new StringBuilder();
    for (val profileEntry : Multimaps.index(connections, e -> e.edge().profile).asMap().entrySet()) {
      sb.append(profileEntry.getKey()).append('\n');
      for (val nodeEntry : profileEntry.getValue()) {
        val coefficient = nodeEntry.edge().distribution.getMode();
        sb.append(nodeEntry.node()).append(": ").append(coefficient);
        if (coefficient >= 1) {
          sb.append("*");
        }
        sb.append('\n');
      }
    }
    if (sb.length() > 0) {
      sb.setLength(sb.length() - 1);
    }
    return sb.toString();
  }

  @RequiredArgsConstructor
  public static class Posteriors implements Serializable, Iterable<Entry<Posterior>> {
    private final Prior owner;
    private final Map<Key<Posterior>, Edge> backing = new HashMap<>();

    @Override
    public Iterator<Entry<Posterior>> iterator() {
      val backing = this.backing.entrySet().iterator();
      return new Iterator<Entry<Posterior>>() {
        Map.Entry<Key<Posterior>, Edge> current;

        @Override
        public boolean hasNext() {
          return backing.hasNext();
        }

        @Override
        public Entry<Posterior> next() {
          current = backing.next();
          return new Entry<>(current.getKey().node(), current.getValue());
        }

        @Override
        public void remove() {
          current.getKey().node().getPriors().backing.remove(new Key<>(owner, current.getKey().profile()));
          backing.remove();
        }
      };
    }

    public Edge getEdge(final Posterior posterior, final IntegrationProfile profile) {
      return backing.computeIfAbsent(new Key<>(posterior, profile), (__) -> {
        val edge = new Edge(posterior, profile);
        posterior.getPriors().backing.put(new Key<>(new WeakPrior(owner), profile), edge);
        return edge;
      });
    }

    @Override
    public String toString() {
      return Connections.toString(this);
    }

    public void clear() {
      val it = iterator();
      while (it.hasNext()) {
        it.next();
        it.remove();
      }
    }
  }

  @RequiredArgsConstructor
  public static class Priors implements Serializable, Iterable<Entry<Prior>> {
    private final Posterior owner;
    private final Map<Key<WeakPrior>, Edge> backing = new HashMap<>();

    @Override
    public Iterator<Entry<Prior>> iterator() {
      val backing = this.backing.entrySet()
          .iterator();

      return new Iterator<Entry<Prior>>() {
        Entry<Prior> current, next;
        {
          advance();
        }

        @Override
        public boolean hasNext() {
          return next != null;
        }

        @Override
        public Entry<Prior> next() {
          current = next;
          advance();
          return current;
        }

        private void advance() {
          while (backing.hasNext()) {
            val candidate = backing.next();
            next = new Entry<>(candidate.getKey().node().ref.get(), candidate.getValue());
            if (next.node == null) {
              backing.remove();
            } else {
              return;
            }
          }
          next = null;
        }

        @Override
        public void remove() {
          current.node().getPosteriors().backing.remove(new Key<>(owner, current.edge().profile));
          backing.remove();
        }
      };
    }

    @Override
    public String toString() {
      return Connections.toString(this);
    }
  }

  private static final String INDENT = "  ";

  private static void debugPriors(final StringBuilder sb, final Posterior node, final String indent) {
    sb.append(indent).append('[');
    val it = node.getPriors().iterator();
    while (it.hasNext()) {
      debugPrior(sb, it.next());
      if (it.hasNext()) {
        sb.append(", ");
      }
    }
    sb.append("] -> ");
  }

  private static void debugPosteriors(final StringBuilder sb, final Prior node, final String indent,
      final int maxDepth) {
    val it = node.getPosteriors().iterator();

    if (maxDepth == 0) {
      if (it.hasNext()) {
        sb.append('\n').append(indent).append("...");
      }
      return;
    }

    while (it.hasNext()) {
      val posterior = it.next();
      sb.append('\n');
      debugPriors(sb, posterior.node(), indent);
      sb.append(posterior.node());
      if (posterior.node() instanceof Prior prior) {
        debugPosteriors(sb, prior, indent + INDENT, maxDepth - 1);
      }
    }
  }

  public static String debugGraph(final Node node, final int maxDepth) {
    val sb = new StringBuilder();

    if (node instanceof Posterior posterior) {
      debugPriors(sb, posterior, "");
    }
    sb.append(node);
    if (node instanceof Prior prior) {
      debugPosteriors(sb, prior, INDENT, maxDepth);
    }

    return sb.toString();
  }

  private static void debugPrior(final StringBuilder sb, final Entry<Prior> prior) {
    sb.append(prior.node())
        .append(": ")
        .append(prior.node().getTrace().evaluate(Scheduler.global.now(), prior.edge().profile))
        .append("/")
        .append(prior.edge().distribution.getMode())
        .append('@')
        .append(prior.edge().profile);
  }

  public static String debugPriors(final Posterior node) {
    val sb = new StringBuilder();
    val it = Multimaps.index(node.getPriors(), Entry<Prior>::node).asMap().entrySet().iterator();
    while (it.hasNext()) {
      val entry = it.next();
      sb.append(entry.getKey())
          .append(": ");
      val curveIt = entry.getValue().iterator();
      while (curveIt.hasNext()) {
        debugPrior(sb, curveIt.next());

        if (curveIt.hasNext()) {
          sb.append(", ");
        }
      }

      if (it.hasNext()) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }
}
