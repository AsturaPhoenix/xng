package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NodeMap extends NodeValueMapAdapter<Node> implements Serializable {
    private static final long serialVersionUID = -4775598967533688683L;

    public NodeMap() {
        super(new NodeKeyMap<>());
    }

    public NodeMap(final Map<Node, Node> source) {
        super(new NodeKeyMap<>(source.size()));
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
