package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class NodeMap extends NodeValueMapAdapter<Node> implements Serializable {
    private static final long serialVersionUID = -4775598967533688683L;

    @Override
    protected void initBacking() {
        backing = new NodeKeyMap<>();
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
        serialize(o);
    }

    private void readObject(final ObjectInputStream o) throws IOException, ClassNotFoundException {
        deserialize(o);
    }
}
