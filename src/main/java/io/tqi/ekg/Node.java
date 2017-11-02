package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import javafx.geometry.Point3D;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

public class Node implements Serializable {
    private static final long serialVersionUID = -4340465118968553513L;

    private static final long DEFAULT_REFRACTORY = 12;

    @Getter
    private final Serializable value;

    @Getter
    private long lastActivation;

    @Getter
    private final Synapse synapse = new Synapse();

    @Getter
    private long refactory = DEFAULT_REFRACTORY;

    private transient Subject<Long> rxInput;
    private transient Observable<Long> rxOutput;
    private transient Subject<Object> rxChange;

    @Getter
    private Point3D location;

    public void setLocation(final Point3D value) {
        location = value;
        rxChange.onNext(this);
    }

    @Getter
    private String comment;

    public void setComment(final String value) {
        comment = value;
        rxChange.onNext(this);
    }

    public Node() {
        this.value = null;
        init();
    }

    public Node(final Serializable value) {
        this.value = value;
        init();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            rxInput.onComplete();
            rxChange.onComplete();
        } finally {
            super.finalize();
        }
    }

    private void init() {
        rxInput = PublishSubject.create();
        // The share is necessary because this node's rxOutput subscription sets
        // lastActivation, which means filter will only produce a correct result
        // once per activation.
        rxOutput = rxInput.filter(t -> t - lastActivation >= refactory).share();
        rxChange = PublishSubject.create();

        synapse.rxActivate().subscribe(t -> activate());
        rxOutput.subscribe(t -> lastActivation = t);
        synapse.rxChange().subscribe(s -> rxChange.onNext(this));
    }

    private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
        stream.defaultReadObject();
        init();
    }

    public Observable<Object> rxChange() {
        return rxChange;
    }

    public void setRefractory(final long refractory) {
        this.refactory = refractory;
        rxChange.onNext(this);
    }

    public void activate() {
        rxInput.onNext(System.currentTimeMillis());
        // Not emitting a change on activate should be okay since as long as
        // there are no structural changes, there's no absolute need to save.
        // Any structural changes will result in an rxChange.
    }

    public Observable<Long> rxActivate() {
        return rxOutput;
    }

    private final ConcurrentMap<Node, Node> properties = new ConcurrentHashMap<>();

    @RequiredArgsConstructor
    public static class PropertySet {
        public final Node object, property, value;
        public final boolean getOrCreate;
    }

    public Node setProperty(final Node property, final Node value) {
        if (value == null) {
            properties.remove(property);
        } else {
            properties.put(property, value);
        }
        rxChange.onNext(new PropertySet(this, property, value, false));

        return this;
    }

    public Node clearProperties() {
        properties.clear();
        rxChange.onNext(this);
        return this;
    }

    public Node setProperty(final Node property, final Optional<Node> value) {
        return setProperty(property, value.orElse(null));
    }

    public Node getProperty(final Node property) {
        return properties.get(property);
    }

    public Node getOrCreateProperty(final Node property, final KnowledgeBase kb) {
        final boolean[] computed = new boolean[1];
        final Node propNode = properties.computeIfAbsent(property, k -> {
            computed[0] = true;
            return kb.node();
        });
        if (computed[0]) {
            rxChange.onNext(new PropertySet(this, property, propNode, true));
        }
        return propNode;
    }

    public Map<Node, Node> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public String toString() {
        return value + "@" + Integer.toHexString(hashCode());
    }

    /**
     * @return next
     */
    public Node then(final Node next) {
        next.getSynapse().setCoefficient(this, 1);
        return next;
    }
}
