package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NodeValueMap<K> extends NodeValueMapAdapter<K> implements Serializable {
    private static final long serialVersionUID = -6252345712493522984L;

    public NodeValueMap() {
        super(new ConcurrentHashMap<>());
    }

    public NodeValueMap(final int initialCapacity) {
        super(new ConcurrentHashMap<>(initialCapacity));
    }

    public NodeValueMap(final Map<? extends K, Node> source) {
        this(source.size());
        putAll(source);
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
        serialize(o);
    }

    private void readObject(final ObjectInputStream o) throws IOException, ClassNotFoundException {
        backing = new ConcurrentHashMap<>();
        deserialize(o);
    }
}
