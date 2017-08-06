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
import lombok.Getter;

public class Node implements Serializable {
    private static final long serialVersionUID = -4340465118968553513L;

    private static final long DEFAULT_REFRACTORY = 1000 / 60;

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

    public Node() {
        this.value = null;
        init();
    }

    public Node(final Serializable value) {
        this.value = value;
        init();
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
        synapse.rxChange().subscribe(t -> rxChange.onNext(this));
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

    public Node setProperty(final Node property, final Node value) {
        if (value == null) {
            properties.remove(property);
        } else {
            properties.put(property, value);
        }
        rxChange.onNext(this);

        return this;
    }

    public Node setProperty(final Node property, final Optional<Node> value) {
        return setProperty(property, value.orElse(null));
    }

    public Node getProperty(final Node property) {
        return properties.get(property);
    }

    public Map<Node, Node> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    @Override
    public String toString() {
        return value + "@" + Integer.toHexString(hashCode());
    }
}
