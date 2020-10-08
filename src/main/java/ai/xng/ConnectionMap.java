package ai.xng;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.MapMaker;

import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ConnectionMap {
  @RequiredArgsConstructor
  public static class PosteriorMap extends AbstractMap<Posterior, Distribution> implements Serializable {
    private static final long serialVersionUID = 1L;

    private class EntrySet extends AbstractSet<Entry<Posterior, Distribution>> implements Serializable {
      private static final long serialVersionUID = 1L;

      @Override
      public Iterator<Entry<Posterior, Distribution>> iterator() {
        return new Iterator<>() {
          private final Iterator<Entry<Posterior, Distribution>> backing = PosteriorMap.this.backing.entrySet()
              .iterator();
          private Entry<Posterior, Distribution> current;

          @Override
          public boolean hasNext() {
            return backing.hasNext();
          }

          @Override
          public Entry<Posterior, Distribution> next() {
            current = backing.next();
            return new Entry<>() {
              Entry<Posterior, Distribution> backing = current;

              @Override
              public Posterior getKey() {
                return backing.getKey();
              }

              @Override
              public Distribution getValue() {
                return backing.getValue();
              }

              @Override
              public Distribution setValue(final Distribution value) {
                backing.getKey()
                    .getPriors().backing.put(owner, value);
                return backing.setValue(value);
              }
            };
          }

          public void remove() {
            backing.remove();
            current.getKey()
                .getPriors().backing.remove(owner);
          }
        };
      }

      @Override
      public boolean remove(final Object o) {
        if (backing.entrySet()
            .remove(o)) {
          @SuppressWarnings("unchecked")
          val entry = (Entry<Posterior, Distribution>) o;
          entry.getKey()
              .getPriors().backing.remove(owner);
          return true;
        }
        return false;
      }

      @Override
      public int size() {
        return backing.size();
      }
    }

    private final Prior owner;
    private final Map<Posterior, Distribution> backing = new HashMap<>();
    private final EntrySet entrySet = new EntrySet();

    @Override
    public Set<Entry<Posterior, Distribution>> entrySet() {
      return entrySet;
    }

    @Override
    public Distribution get(final Object key) {
      return backing.get(key);
    }

    @Override
    public Distribution put(final Posterior key, final Distribution value) {
      key.getPriors().backing.put(owner, value);
      return backing.put(key, value);
    }

    @Override
    public Distribution remove(final Object key) {
      val old = backing.remove(key);
      if (old != null) {
        ((Posterior) key).getPriors().backing.remove(owner);
        return old;
      }
      return null;
    }
  }

  public static class PriorMap extends AbstractMap<Prior, Distribution> {
    private final Map<Prior, Distribution> backing = new MapMaker().weakKeys()
        .makeMap();

    @Override
    public Set<Entry<Prior, Distribution>> entrySet() {
      // TODO Auto-generated method stub
      return null;
    }
  }
}
