package io.tqi.asn;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.Getter;
import lombok.Setter;

public class Node implements Serializable {
	private static final long serialVersionUID = -4340465118968553513L;

	private static final long DEFAULT_REFRACTORY = 1000 / 60;

	@Getter
	private Serializable value;

	@Getter
	private long lastActivation;

	@Getter
	private final Synapse synapse = new Synapse();

	@Getter
	@Setter
	private long refactory = DEFAULT_REFRACTORY;

	private transient Subject<Long> rxInput;
	private transient Observable<Long> rxOutput;
	private transient Subject<Void> rxChange;

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
		synapse.rxChange().subscribe(t -> rxChange.onNext(null));
	}

	private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
		stream.defaultReadObject();
		init();
	}

	public Observable<Void> rxChange() {
		return rxChange;
	}

	public void activate() {
		rxInput.onNext(System.currentTimeMillis());
	}

	public Observable<Long> rxActivate() {
		return rxOutput;
	}

	private final ConcurrentMap<Node, Node> properties = new ConcurrentHashMap<>();

	public void setProperty(final Node property, final Node value) {
		properties.put(property, value);
		rxChange.onNext(null);
	}

	public Node getProperty(final Node property) {
		return properties.get(property);
	}

	public Map<Node, Node> getProperties() {
		return Collections.unmodifiableMap(properties);
	}
}
