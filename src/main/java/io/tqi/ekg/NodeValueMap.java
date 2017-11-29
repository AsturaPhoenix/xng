package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class NodeValueMap<K> extends NodeMapBase<K, Node, Node.Ref> implements Serializable {
    private static final long serialVersionUID = -6252345712493522984L;

    private class EntrySet extends EntrySetBase<K, Node, Node.Ref> {
        EntrySet() {
            super(NodeValueMap.this.backing);
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
                            e.setValue(ref(e.getKey(), value));
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

    public NodeValueMap() {
        init();
    }

    protected void init() {
        initBacking();
        entrySet = new EntrySet();
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
    public Node put(K key, Node value) {
        final Node.Ref old = backing.put(key, ref(key, value));
        if (old == null) {
            return null;
        } else {
            old.dispose();
            return old.get();
        }
    }

    @Override
    public Node computeIfAbsent(K key, Function<? super K, ? extends Node> mappingFunction) {
        return backing.computeIfAbsent(key, k -> ref(k, mappingFunction.apply(k))).get();
    }
}
