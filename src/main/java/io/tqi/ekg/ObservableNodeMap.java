package io.tqi.ekg;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;

import io.tqi.ekg.Node.Ref;
import io.tqi.ekg.NodeKeyMap.Record;
import javafx.beans.InvalidationListener;
import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.MapChangeListener.Change;
import javafx.collections.ObservableMap;
import lombok.Synchronized;

public class ObservableNodeMap extends NodeMap implements ObservableMap<Node, Node> {
    private static final long serialVersionUID = 4781717644951136694L;

    @Override
    protected void initBacking() {
        backing = new NodeKeyMap<Ref>() {
            private static final long serialVersionUID = 4525303244246837401L;

            @Override
            protected void initBacking() {
                backing = FXCollections
                        .synchronizedObservableMap(FXCollections.observableMap(new LinkedHashMap<>(16, .75f, true)));
            }
        };
    }

    private ObservableMap<Node, Record<Ref>> backing() {
        return (ObservableMap<Node, Record<Ref>>) ((NodeKeyMap<Ref>) backing).backing;
    }

    /**
     * @return an object which may be synchronized during iteration.
     */
    public Object mutex() {
        final Object backing = backing();
        try {
            final Field mutex = backing.getClass().getSuperclass().getDeclaredField("mutex");
            mutex.setAccessible(true);
            return mutex.get(backing);
        } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void addListener(InvalidationListener listener) {
        backing().addListener(listener);
    }

    @Override
    public void removeListener(InvalidationListener listener) {
        backing().removeListener(listener);
    }

    private transient ListMultimap<MapChangeListener<? super Node, ? super Node>, MapChangeListener<Node, Record<Ref>>> listeners;

    private MapChangeListener<Node, Record<Ref>> adaptListener(
            final MapChangeListener<? super Node, ? super Node> listener) {
        return c -> listener.onChanged(new Change<Node, Node>(this) {
            @Override
            public Node getKey() {
                return c.getKey();
            }

            @Override
            public Node getValueAdded() {
                try {
                    return c.getValueAdded().value.get();
                } catch (final NullPointerException e) {
                    return null;
                }
            }

            @Override
            public Node getValueRemoved() {
                try {
                    return c.getValueRemoved().value.get();
                } catch (final NullPointerException e) {
                    return null;
                }
            }

            @Override
            public boolean wasAdded() {
                return c.wasAdded();
            }

            @Override
            public boolean wasRemoved() {
                return c.wasRemoved();
            }
        });
    }

    @Synchronized
    @Override
    public void addListener(MapChangeListener<? super Node, ? super Node> listener) {
        if (listeners == null) {
            listeners = Multimaps.synchronizedListMultimap(ArrayListMultimap.create());
        }

        final MapChangeListener<Node, Record<Ref>> adapted = adaptListener(listener);
        listeners.put(listener, adapted);
        backing().addListener(adapted);
    }

    @Synchronized
    @Override
    public void removeListener(MapChangeListener<? super Node, ? super Node> listener) {
        if (listeners == null)
            return;

        final List<MapChangeListener<Node, Record<Ref>>> adapted = listeners.get(listener);
        if (!adapted.isEmpty()) {
            backing().removeListener(adapted.remove(0));
        }
    }

}
