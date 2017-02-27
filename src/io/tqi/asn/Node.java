package io.tqi.asn;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;

@RequiredArgsConstructor
public class Node implements Serializable {
  @Value
  public static class SetPropertyEvent {
    Node property, value;
  }
  
  private static final long serialVersionUID = -4340465118968553513L;
  
  @Getter
  private final Serializable value;
  
  @Getter
  private final Synapse synapse = new Synapse();
  
  private final ConcurrentMap<Node, Node> properties = new ConcurrentHashMap<>();
  
  private final transient Subject<SetPropertyEvent> setProperty = PublishSubject.create();
  
  public Observable<SetPropertyEvent> setProperty() {
    return setProperty;
  }
  
  public Node getProperty(final Node property) {
    return properties.get(property);
  }
  
  public void setProperty(final Node property, final Node value) {
    properties.put(property, value);
    setProperty.onNext(new SetPropertyEvent(property, value));
  }
}
