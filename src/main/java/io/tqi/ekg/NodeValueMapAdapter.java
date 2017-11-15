package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Function;

public class NodeValueMapAdapter<K> extends NodeMapBase<K, Node, Node.Ref> {
    private static class EntrySet<K> extends EntrySetBase<K, Node, Node.Ref> {
        EntrySet(final Map<K, Node.Ref> backing) {
            super(backing);
        }

        @Override
        public Iterator<Entry<K, Node>> iterator() {
            return new IteratorBase<Entry<K, Node.Ref>>(backing.entrySet().iterator()) {
                @Override
                protected Node.Ref getWrappedValue(Entry<K, Node.Ref> e) {
                    return e.getValue();
                }

                @Override
                protected Entry<K, Node> getEntry(Entry<K, Node.Ref> e) {
                    return new Entry<K, Node>() {
                        @Override
                        public K getKey() {
                            return e.getKey();
                        }

                        @Override
                        public Node getValue() {
                            return e.getValue().get();
                        }

                        @Override
                        public Node setValue(Node value) {
                            final Node.Ref old = e.getValue();
                            old.dispose();
                            e.setValue(value.ref(() -> backing.remove(e.getKey())));
                            return old.get();
                        }
                    };
                }
            };
        }

        @Override
        protected Node unwrapValue(Node.Ref wrapped) {
            return wrapped.get();
        }

        @Override
        protected void disposeValue(Node.Ref wrapped) {
            wrapped.dispose();
        }
    }

    public NodeValueMapAdapter(final Map<K, Node.Ref> backing) {
        this.backing = backing;
        entrySet = new EntrySet<>(backing);
    }

    /**
     * Deserialization constructor.
     */
    protected NodeValueMapAdapter() {
    }

    @Override
    protected void deserialize(ObjectInputStream o) throws IOException, ClassNotFoundException {
        entrySet = new EntrySet<>(backing);
        super.deserialize(o);
    }

    @Override
    public Node put(K key, Node value) {
        final Node.Ref old = backing.put(key, value.ref(() -> backing.remove(key)));
        if (old == null) {
            return null;
        } else {
            old.dispose();
            return old.get();
        }
    }

    @Override
    public Node computeIfAbsent(K key, Function<? super K, ? extends Node> mappingFunction) {
        return backing.computeIfAbsent(key, k -> mappingFunction.apply(k).ref(() -> backing.remove(k))).get();
    }
}
