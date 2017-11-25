package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OptionalDataException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;

public class NodeMapBase<K, V, X> extends AbstractMap<K, V> {
    @RequiredArgsConstructor
    protected static abstract class EntrySetBase<K, V, X> extends AbstractSet<Entry<K, V>> {
        protected abstract class IteratorBase<BackingElement> implements Iterator<Entry<K, V>> {
            private final Iterator<BackingElement> bi;
            private X current;

            protected IteratorBase(final Iterator<BackingElement> backing) {
                bi = backing;
            }

            @Override
            public final boolean hasNext() {
                return bi.hasNext();
            }

            @Override
            public final Entry<K, V> next() {
                final BackingElement e = bi.next();
                current = getWrappedValue(e);
                return getEntry(e);
            }

            @Override
            public final void remove() {
                disposeValue(current);
                bi.remove();
            };

            protected abstract X getWrappedValue(BackingElement e);

            protected abstract Entry<K, V> getEntry(BackingElement e);
        }

        protected final Map<K, X> backing;

        @Override
        public final int size() {
            return backing.size();
        }

        @Override
        public final boolean removeAll(java.util.Collection<?> c) {
            if (c instanceof Set) {
                return super.removeAll(c);
            } else {
                boolean modified = false;
                for (final Object e : c) {
                    modified |= remove(e);
                }
                return modified;
            }
        }

        protected abstract V unwrapValue(X wrapped);

        protected abstract void disposeValue(X wrapped);

        @Override
        public final boolean contains(Object o) {
            if (o instanceof Entry) {
                final Entry<?, ?> entry = (Entry<?, ?>) o;
                final X wrappedValue = backing.get(entry.getKey());
                return wrappedValue != null && Objects.equals(unwrapValue(wrappedValue), entry.getValue());
            } else {
                return false;
            }
        }

        @Override
        public final boolean remove(Object o) {
            if (o instanceof Entry) {
                final Entry<?, ?> entry = (Entry<?, ?>) o;
                final X wrappedValue = backing.get(entry.getKey());
                if (wrappedValue != null)
                    disposeValue(wrappedValue);
                return backing.remove(entry.getKey(), wrappedValue);
            } else {
                return false;
            }
        }

        @Override
        public final void clear() {
            // best effort only
            try {
                for (final X wrappedValue : backing.values()) {
                    disposeValue(wrappedValue);
                }
            } catch (RuntimeException e) {
            }
            backing.clear();
        }
    }

    protected transient Map<K, X> backing;
    protected transient EntrySetBase<K, V, X> entrySet;

    protected void serialize(final ObjectOutputStream o) throws IOException {
        o.defaultWriteObject();
        for (final Entry<K, V> entry : entrySet) {
            o.writeObject(entry.getKey());
            o.writeObject(entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    protected void deserialize(ObjectInputStream o) throws IOException, ClassNotFoundException {
        o.defaultReadObject();

        try {
            while (true) {
                put((K) o.readObject(), (V) o.readObject());
            }
        } catch (final OptionalDataException e) {
            if (!e.eof)
                throw e;
        }
    }

    protected Node.Ref ref(final K key, final Node node) {
        return node.ref(() -> backing.remove(key));
    }

    @Override
    public final Set<Entry<K, V>> entrySet() {
        return entrySet;
    }

    @Override
    public final boolean containsKey(Object key) {
        return backing.containsKey(key);
    }

    @Override
    public final V get(Object key) {
        final X wrappedValue = backing.get(key);
        if (wrappedValue == null) {
            return null;
        } else {
            return entrySet.unwrapValue(wrappedValue);
        }
    }

    @Override
    public final V remove(Object key) {
        final X wrappedValue = backing.remove(key);
        if (wrappedValue == null) {
            return null;
        } else {
            entrySet.disposeValue(wrappedValue);
            return entrySet.unwrapValue(wrappedValue);
        }
    }

    @Override
    public final Set<K> keySet() {
        return backing.keySet();
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }
}
