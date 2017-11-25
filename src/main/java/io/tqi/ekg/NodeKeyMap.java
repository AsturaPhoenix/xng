package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;

public class NodeKeyMap<V> extends NodeMapBase<Node, V, NodeKeyMap.Record<V>> implements Serializable {
    private static final long serialVersionUID = 538716271738817041L;

    @RequiredArgsConstructor
    protected static class Record<V> {
        final Node.Ref keyRef;
        V value;
    }

    private static class EntrySet<V> extends EntrySetBase<Node, V, Record<V>> {
        EntrySet(final Map<Node, Record<V>> backing) {
            super(backing);
        }

        @Override
        public Iterator<Entry<Node, V>> iterator() {
            return new IteratorBase<Record<V>>(backing.values().iterator()) {
                @Override
                protected Record<V> getWrappedValue(Record<V> e) {
                    return e;
                }

                @Override
                protected Entry<Node, V> getEntry(Record<V> e) {
                    return new Entry<Node, V>() {
                        @Override
                        public Node getKey() {
                            return e.keyRef.get();
                        }

                        @Override
                        public V getValue() {
                            return e.value;
                        }

                        @Override
                        public V setValue(V value) {
                            final V old = e.value;
                            e.value = value;
                            return old;
                        }
                    };
                }
            };
        }

        @Override
        protected V unwrapValue(Record<V> wrapped) {
            return wrapped.value;
        }

        @Override
        protected void disposeValue(Record<V> wrapped) {
            wrapped.keyRef.dispose();
        }
    }

    public NodeKeyMap() {
        init();
    }

    private void init() {
        initBacking();
        entrySet = new EntrySet<>(backing);
    }

    protected void initBacking() {
        backing = new ConcurrentHashMap<>();
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
        serialize(o);
    }

    private void readObject(final ObjectInputStream o) throws IOException, ClassNotFoundException {
        init();
        deserialize(o);
    }

    @Override
    public V put(Node key, V value) {
        final Record<V> record = backing.computeIfAbsent(key, k -> new Record<>(ref(k, k)));
        final V oldValue = record.value;
        record.value = value;
        return oldValue;
    }

    @Override
    public V computeIfAbsent(Node key, Function<? super Node, ? extends V> mappingFunction) {
        return backing.computeIfAbsent(key, k -> {
            final Record<V> record = new Record<>(ref(k, k));
            record.value = mappingFunction.apply(k);
            return record;
        }).value;
    }
}
