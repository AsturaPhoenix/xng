package io.tqi.asn;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

public class Synapse implements Serializable {
  private static final long serialVersionUID = 1779165354354490167L;

  private static class Activation {
    static final float DEFAULT_DECAY_RATE = 1f / 30000;

    float coefficient;
    float decayRate; // linear for now
    final Node node;
    final transient Disposable subscription;

    Activation(final Node node, final Disposable subscription) {
      this.coefficient = 1;
      decayRate = DEFAULT_DECAY_RATE;
      this.node = node;
      this.subscription = subscription;
    }

    float getValue(final long time) {
      return coefficient * Math.max(0, 1 - (time - node.getLastActivation()) * decayRate);
    }
  }

  private final ConcurrentMap<Node, Activation> inputs = new ConcurrentHashMap<>();

  public float getValue(final long time) {
    float value = 0;
    for (final Activation activation : inputs.values()) {
      value += activation.getValue(time);
    }
    return value;
  }

  private final transient Subject<Long> rxInput = PublishSubject.create();
  private final transient Observable<Long> rxOutput = rxInput.filter(t -> getValue(t) >= 1);
  private final transient Subject<Void> rxChange = PublishSubject.create();

  public Observable<Long> rxActivate() {
    return rxOutput;
  }

  public Observable<Void> rxChange() {
    return rxChange;
  }

  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.writeInt(inputs.size());
    for (final Entry<Node, Activation> entry : inputs.entrySet()) {
      o.writeObject(entry.getKey());
      o.writeFloat(entry.getValue().coefficient);
      o.writeFloat(entry.getValue().decayRate);
    }
  }

  private void readObject(ObjectInputStream o) throws IOException, ClassNotFoundException {
    final int size = o.readInt();
    for (int i = 0; i < size; i++) {
      final Node node = (Node) o.readObject();
      final Activation activation = NewActivation(node);
      activation.coefficient = o.readFloat();
      activation.decayRate = o.readFloat();
    }
  }

  private Activation NewActivation(final Node source) {
    return new Activation(source, source.rxActivate().subscribe(rxInput::onNext));
  }

  public void SetCoefficient(final Node node, final float coefficient) {
    inputs.computeIfAbsent(node, this::NewActivation).coefficient = coefficient;
    rxChange.onNext(null);
  }

  public void SetDecayRate(final Node node, final float decayRate) {
    inputs.computeIfAbsent(node, this::NewActivation).decayRate = decayRate;
    rxChange.onNext(null);
  }

  public void Dissociate(final Node node) {
    inputs.remove(node).subscription.dispose();
    rxChange.onNext(null);
  }
}
