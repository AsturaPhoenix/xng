package ai.xng;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.google.common.collect.MapMaker;

import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.experimental.UtilityClass;

@UtilityClass
public class Connections {
  public static record Entry<T> (T node, IntegrationProfile profile, Distribution distribution) {
  }

  private static record Key<T extends Serializable> (T node, IntegrationProfile profile) implements Serializable {
    private static final long serialVersionUID = 1L;
  }

  @RequiredArgsConstructor
  public static class Posteriors implements Serializable, Iterable<Entry<Posterior>> {
    private static final long serialVersionUID = 1L;

    private final Prior owner;
    private final Map<Key<Posterior>, Distribution> backing = new HashMap<>();

    @Override
    public Iterator<Entry<Posterior>> iterator() {
      val backing = this.backing.entrySet().iterator();
      return new Iterator<Entry<Posterior>>() {
        Map.Entry<Key<Posterior>, Distribution> current;

        @Override
        public boolean hasNext() {
          return backing.hasNext();
        }

        @Override
        public Entry<Posterior> next() {
          current = backing.next();
          return new Entry<>(current.getKey().node(), current.getKey().profile(), current.getValue());
        }

        @Override
        public void remove() {
          current.getKey().node().getPriors().backing.remove(new Key<>(owner, current.getKey().profile()));
          backing.remove();
        }
      };
    }

    public void setCoefficient(final Posterior posterior, final IntegrationProfile profile, final float coefficient) {
      setCoefficient(posterior, profile, coefficient, Distribution.DEFAULT_WEIGHT);
    }

    public void setCoefficient(final Posterior posterior, final IntegrationProfile profile, final float coefficient,
        final float weight) {
      backing.compute(new Key<>(posterior, profile), (__, distribution) -> {
        if (distribution == null) {
          distribution = new UnimodalHypothesis(coefficient, weight);
          posterior.getPriors().backing.put(new Key<>(owner, profile), distribution);
          return distribution;
        } else {
          distribution.set(coefficient, weight);
          return distribution;
        }
      });
    }

    @Override
    public String toString() {
      return backing.toString();
    }
  }

  @RequiredArgsConstructor
  public static class Priors implements Serializable, Iterable<Entry<Prior>> {
    private static final long serialVersionUID = 1L;

    private final Posterior owner;
    // Use MapMaker... rather than WeakHashMap for serializability.
    private final Map<Key<Prior>, Distribution> backing = new MapMaker().weakKeys().makeMap();

    @Override
    public Iterator<Entry<Prior>> iterator() {
      val backing = this.backing.entrySet()
          .iterator();
      return new Iterator<Entry<Prior>>() {
        Map.Entry<Key<Prior>, Distribution> current;

        @Override
        public boolean hasNext() {
          return backing.hasNext();
        }

        @Override
        public Entry<Prior> next() {
          current = backing.next();
          return new Entry<>(current.getKey().node(), current.getKey().profile(), current.getValue());
        }

        @Override
        public void remove() {
          current.getKey().node().getPosteriors().backing.remove(new Key<>(owner, current.getKey().profile()));
          backing.remove();
        }
      };
    }

    @Override
    public String toString() {
      return backing.toString();
    }
  }
}
