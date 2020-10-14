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
  public static record Entry<T extends Serializable> (T node, Distribution distribution) implements Serializable {
    private static final long serialVersionUID = 1L;
  }

  @RequiredArgsConstructor
  public static class Posteriors implements Serializable, Iterable<Entry<Posterior>> {
    private static final long serialVersionUID = 1L;

    private final Prior owner;
    private final Map<Posterior, Distribution> backing = new HashMap<>();

    @Override
    public Iterator<Entry<Posterior>> iterator() {
      val backing = this.backing.entrySet()
          .iterator();
      return new Iterator<Entry<Posterior>>() {
        Map.Entry<Posterior, Distribution> current;

        @Override
        public boolean hasNext() {
          return backing.hasNext();
        }

        @Override
        public Entry<Posterior> next() {
          current = backing.next();
          return new Entry<>(current.getKey(), current.getValue());
        }

        @Override
        public void remove() {
          current.getKey()
              .getPriors().backing.remove(owner);
          backing.remove();
        }
      };
    }

    public void setCoefficient(final Posterior posterior, final float coefficient) {
      setCoefficient(posterior, coefficient, Distribution.DEFAULT_WEIGHT);
    }

    public void setCoefficient(final Posterior posterior, final float coefficient, final float weight) {
      backing.compute(posterior, (__, profile) -> {
        if (profile == null) {
          profile = new UnimodalHypothesis(coefficient, weight);
          posterior.getPriors().backing
              .put(owner, profile);
          return profile;
        } else {
          profile.set(coefficient, weight);
          return profile;
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
    private final Map<Prior, Distribution> backing = new MapMaker().weakKeys()
        .makeMap();

    @Override
    public Iterator<Entry<Prior>> iterator() {
      val backing = this.backing.entrySet()
          .iterator();
      return new Iterator<Entry<Prior>>() {
        Map.Entry<Prior, Distribution> current;

        @Override
        public boolean hasNext() {
          return backing.hasNext();
        }

        @Override
        public Entry<Prior> next() {
          current = backing.next();
          return new Entry<>(current.getKey(), current.getValue());
        }

        @Override
        public void remove() {
          current.getKey()
              .getPosteriors().backing.remove(owner);
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
