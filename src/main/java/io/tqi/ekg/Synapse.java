package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.ReplaySubject;
import io.reactivex.subjects.Subject;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * Represents the incoming logical junction of node signals towards a specific
 * node.
 * 
 * Minimal effort is made to preserve activation across serialization boundaries
 * since behavior while the system is down is discontinuous anyway. In the
 * future, it is likely that either we will switch to relative time and fully
 * support serialization or else completely clear activation on deserialization.
 */
public class Synapse implements Serializable, Iterable<Entry<Node, Synapse.Profile>>, ChangeObservable<Synapse> {
    private static final long serialVersionUID = 1779165354354490167L;

    public static final long DEBOUNCE_PERIOD = 2;
    private static final double DECAY_MARGIN = .11;
    private static final double THRESHOLD = 1;

    public static class Profile {
        @Getter
        private float coefficient;
        @Getter
        private long decayPeriod; // linear for now
        private final Node node;
        private final Disposable subscription;

        private Profile(final Node node, final Disposable subscription) {
            this.coefficient = 1;
            this.node = node;
            resetDecay();
            this.subscription = subscription;
        }

        public void resetDecay() {
            // The default decay should be roughly proportional to the
            // refractory period of the source node as nodes with shorter
            // refractory periods are likely to be evoked more often, possibly
            // spuriously, and should thus get out of the way faster. By the
            // time the refractory period has elapsed and the node may thus be
            // activated again, we want this activation to be decayed by at the
            // decay margin.
            decayPeriod = Math.max((long) (node.getRefractory() / DECAY_MARGIN), 1);
        }

        public float getValue(final long time) {
            long dt = Math.max(time - node.getLastActivation(), 0);
            return dt >= decayPeriod ? 0 : coefficient * (1 - dt / (float) decayPeriod);
        }

        private long getZero() {
            return node.getLastActivation() + decayPeriod;
        }
    }

    private transient NodeKeyMap<Profile> inputs;

    private transient Subject<Long> rxInput;
    private transient Observable<Long> rxOutput;
    private transient Subject<Synapse> rxChange;
    private transient Subject<Evaluation> rxValue;

    public Synapse() {
        init();
    }

    private void init() {
        inputs = new NodeKeyMap<>();
        rxInput = PublishSubject.create();
        rxOutput = rxInput.sample(rxInput.delay(DEBOUNCE_PERIOD, TimeUnit.MILLISECONDS)).distinctUntilChanged()
                .switchMap(this::evaluate);
        rxChange = PublishSubject.create();
        rxValue = ReplaySubject.createWithSize(EVALUATION_HISTORY);
    }

    @RequiredArgsConstructor
    @ToString
    @EqualsAndHashCode
    public static class Evaluation {
        public final long time;
        public final float value;
    }

    public static final int EVALUATION_HISTORY = 5;

    public Observable<Evaluation> rxValue() {
        return rxValue;
    }

    /**
     * Emits an activation signal or schedules a re-evaluation at a future time,
     * depending on current state.
     */
    private Observable<Long> evaluate(final long time) {
        final float value = getValue(time);
        rxValue.onNext(new Evaluation(time, value));

        if (value >= THRESHOLD) {
            return Observable.just(time);
        } else {
            final long nextCrit = getNextCriticalPoint(time);
            return nextCrit == Long.MAX_VALUE ? Observable.empty()
                    : Observable.timer(nextCrit - time, TimeUnit.MILLISECONDS).flatMap(x -> evaluate(nextCrit));
        }
    }

    public float getValue(final long time) {
        float value = 0;
        for (final Profile activation : inputs.values()) {
            value += activation.getValue(time);
        }
        return value;
    }

    /**
     * Gets the next time the synapse should be evaluated if current conditions
     * hold. This is the minimum of the next time the synapse would cross the
     * activation threshold given current conditions, and the zeros of the
     * activations involved. Activations that have already fully decayed do not
     * affect this calculation.
     */
    private long getNextCriticalPoint(final long time) {
        float totalValue = 0, totalDecayRate = 0;
        long nextZero = Long.MAX_VALUE;
        for (final Profile activation : inputs.values()) {
            final float value = activation.getValue(time);
            if (value != 0) {
                totalValue += value;
                totalDecayRate += activation.coefficient / activation.decayPeriod;
                nextZero = Math.min(nextZero, activation.getZero());
            }
        }
        final long untilThresh = (long) ((1 - totalValue) / -totalDecayRate);
        return untilThresh <= 0 ? nextZero : Math.min(untilThresh + time, nextZero);
    }

    public Observable<Long> rxActivate() {
        return rxOutput;
    }

    @Override
    public Observable<Synapse> rxChange() {
        return rxChange;
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
        o.defaultWriteObject();
        o.writeInt(inputs.size());
        for (final Entry<Node, Profile> entry : inputs.entrySet()) {
            o.writeObject(entry.getKey());
            o.writeFloat(entry.getValue().coefficient);
            o.writeLong(entry.getValue().decayPeriod);
        }
    }

    private void readObject(ObjectInputStream o) throws IOException, ClassNotFoundException {
        o.defaultReadObject();
        init();
        final int size = o.readInt();
        for (int i = 0; i < size; i++) {
            final Node node = (Node) o.readObject();
            final Profile activation = newActivation(node);
            activation.coefficient = o.readFloat();
            activation.decayPeriod = o.readLong();
            inputs.put(node, activation);
        }
    }

    private Profile newActivation(final Node source) {
        return new Profile(source, source.rxActivate().subscribe(rxInput::onNext));
    }

    public Synapse setCoefficient(final Node node, final float coefficient) {
        inputs.computeIfAbsent(node, this::newActivation).coefficient = coefficient;
        rxChange.onNext(this);
        return this;
    }

    public float getCoefficient(final Node node) {
        final Profile activation = inputs.get(node);
        return activation == null ? 0 : activation.coefficient;
    }

    /**
     * @param node
     *            the input node
     * @param decayRate
     *            the linear signal decay period, in milliseconds from
     *            activation to 0
     */
    public Synapse setDecayPeriod(final Node node, final long decayPeriod) {
        inputs.computeIfAbsent(node, this::newActivation).decayPeriod = decayPeriod;
        rxChange.onNext(this);
        return this;
    }

    public long getDecayPeriod(final Node node) {
        final Profile activation = inputs.get(node);
        return activation == null ? 0 : activation.decayPeriod;
    }

    public void dissociate(final Node node) {
        final Profile activation = inputs.remove(node);
        if (activation != null) {
            activation.subscription.dispose();
            rxChange.onNext(this);
        }
    }

    @Override
    public Iterator<Entry<Node, Profile>> iterator() {
        return inputs.entrySet().iterator();
    }
}
