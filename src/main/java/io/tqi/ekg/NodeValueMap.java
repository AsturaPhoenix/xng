package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class NodeValueMap<K> extends NodeValueMapAdapter<K> implements Serializable {
    private static final long serialVersionUID = -6252345712493522984L;

    private void writeObject(final ObjectOutputStream o) throws IOException {
        serialize(o);
    }

    private void readObject(final ObjectInputStream o) throws IOException, ClassNotFoundException {
        deserialize(o);
    }
}
