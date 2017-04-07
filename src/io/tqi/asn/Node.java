package io.tqi.asn;

import java.io.Serializable;
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

  public Node(final Serializable value) {
    this.value = value;
  }

  @Getter
  private final Synapse synapse = new Synapse();

  @Getter
  @Setter
  private long refactory = DEFAULT_REFRACTORY;

  private final transient Subject<Long> rxInput = PublishSubject.create();
  private final transient Observable<Long> rxOutput = rxInput
      .filter(t -> t - lastActivation >= refactory);
  private final transient Subject<Void> rxChange = PublishSubject.create();

  {
    synapse.rxActivate().subscribe(t -> activate());
    rxOutput.subscribe(t -> lastActivation = t);
    synapse.rxChange().subscribe(t -> rxChange.onNext(null));
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
  }

  public Node getProperty(final Node property) {
    return properties.get(property);
  }
}
