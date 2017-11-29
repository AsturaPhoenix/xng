package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;

import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import lombok.experimental.Delegate;

public class ObservableNodeMap implements ObservableMap<Node, Node>, Serializable {
    private static final long serialVersionUID = 4781717644951136694L;

    private final NodeMap coreBacking = new NodeMap() {
        private static final long serialVersionUID = 5840027858215499991L;

        @Override
        protected void initBacking() {
            backing = new NodeKeyMap<Node.Ref>() {
                private static final long serialVersionUID = -8585516014272100534L;

                @Override
                protected void initBacking() {
                    backing = new LinkedHashMap<>(16, .75f, true);
                }
            };
        };
    };

    @Delegate
    private transient ObservableMap<Node, Node> observableBacking;

    public ObservableNodeMap() {
        init();
    }

    private void init() {
        observableBacking = FXCollections.synchronizedObservableMap(FXCollections.observableMap(coreBacking));
    }

    private void readObject(final ObjectInputStream o) throws IOException, ClassNotFoundException {
        o.defaultReadObject();
        init();
    }

    /**
     * @return an object which may be synchronized during iteration.
     */
    public Object mutex() {
        try {
            final Field mutex = observableBacking.getClass().getSuperclass().getDeclaredField("mutex");
            mutex.setAccessible(true);
            return mutex.get(observableBacking);
        } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
            throw new RuntimeException(e);
        }
    }
}
