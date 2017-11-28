package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectInputStream.GetField;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import javafx.collections.MapChangeListener;
import javafx.geometry.Point3D;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

public class Node implements Serializable, ChangeObservable<Object> {
    private static final long serialVersionUID = -4340465118968553513L;

    private static final long DEFAULT_REFRACTORY = 50;

    @AllArgsConstructor
    private static class SPoint implements Serializable {
        private static final long serialVersionUID = 4405782167116875037L;
        double x, y, z;
    }

    public static class Ref implements Disposable {
        private Node node;
        private final Disposable cleanup;

        public Node get() {
            return node;
        }

        public Ref(final Node node, final Action deleter) {
            this.node = node;
            cleanup = node.rxDeleted().subscribe(() -> {
                if (deleter != null)
                    deleter.run();
                this.node = null;
            });
        }

        @Override
        public void dispose() {
            cleanup.dispose();
        }

        @Override
        public boolean isDisposed() {
            return cleanup.isDisposed();
        }
    }

    public Ref ref(final Action deleter) {
        return new Ref(this, deleter);
    }

    public Ref ref() {
        return ref(null);
    }

    @Getter
    private Serializable value;

    @Getter
    private long lastActivation;

    @Getter
    private Synapse synapse = new Synapse();

    @Getter
    private long refractory = DEFAULT_REFRACTORY;

    private transient Subject<Long> rxInput;
    @Setter
    private transient Runnable onActivate;
    private transient Subject<Long> rxOutput;
    private transient Observable<Long> rxActivationHistory;
    private transient Subject<Object> rxChange;
    private transient Completable rxDeleted;

    private SPoint location;

    public Point3D getLocation() {
        return location == null ? null : new Point3D(location.x, location.y, location.z);
    }

    public void setLocation(final Point3D value) {
        location = value == null ? null : new SPoint(value.getX(), value.getY(), value.getZ());
        rxChange.onNext(this);
    }

    @Getter
    @Setter
    private boolean pinned;

    @Getter
    private String comment;

    public void setComment(final String value) {
        comment = value;
        rxChange.onNext(this);
    }

    public Node() {
        this(null);
    }

    public Node(final Serializable value) {
        this.value = value;
        preInit();
        postInit();
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

    public static final int ACTIVATION_HISTORY = 5;

    private void preInit() {
        rxInput = PublishSubject.create();
        rxOutput = PublishSubject.create();
        rxActivationHistory = rxOutput.replay(ACTIVATION_HISTORY).autoConnect(0);
        rxInput.observeOn(Schedulers.computation()).subscribe(t -> {
            if (t - lastActivation >= refractory) {
                if (onActivate != null)
                    onActivate.run();
                lastActivation = System.currentTimeMillis();
                rxOutput.onNext(lastActivation);
            }
        });
        rxChange = PublishSubject.create();
        rxDeleted = rxChange.ignoreElements();
    }

    private void postInit() {
        synapse.rxActivate().subscribe(t -> activate());
        synapse.rxChange().subscribe(s -> rxChange.onNext(this));
        properties.addListener((MapChangeListener<Node, Node>) rxChange::onNext);
    }

    @SuppressWarnings("unchecked")
    private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
        preInit();

        final GetField fields = stream.readFields();

        value = (Serializable) fields.get("value", null);
        lastActivation = fields.get("lastActivation", 0L);
        refractory = fields.get("refractory", DEFAULT_REFRACTORY);
        synapse = (Synapse) fields.get("synapse", new Synapse());
        location = (SPoint) fields.get("location", null);
        pinned = fields.get("pinned", false);
        comment = (String) fields.get("comment", null);
        properties = new ObservableNodeMap();
        properties.putAll((Map<Node, Node>) fields.get("properties", new HashMap<>()));

        postInit();
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
        synchronized (properties.mutex()) {
            o.defaultWriteObject();
        }
    }

    @Override
    public Observable<Object> rxChange() {
        return rxChange;
    }

    public Completable rxDeleted() {
        return rxDeleted;
    }

    public void delete() {
        rxInput.onComplete();
        rxChange.onComplete();
        rxOutput.onComplete();
    }

    public void setRefractory(final long refractory) {
        this.refractory = refractory;
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

    public Observable<Long> rxActivationHistory() {
        return rxActivationHistory;
    }

    private ObservableNodeMap properties = new ObservableNodeMap();

    @RequiredArgsConstructor
    public static class PropertySet {
        public final Node object, property, value;
        public final boolean getOrCreate;
    }

    public ObservableNodeMap properties() {
        return properties;
    }

    @Override
    public String toString() {
        return Integer.toHexString(hashCode()) + ": " + comment + " = " + value + " @ " + getLocation();
    }

    public String displayString() {
        return value == null ? comment : value.toString();
    }

    /**
     * @return next
     */
    public Node then(final Node next) {
        next.getSynapse().setCoefficient(this, 1);
        return next;
    }
}
