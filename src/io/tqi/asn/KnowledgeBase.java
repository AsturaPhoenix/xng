package io.tqi.asn;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import lombok.RequiredArgsConstructor;

public class KnowledgeBase implements Serializable, AutoCloseable {
  private static final long serialVersionUID = 4850129606513054849L;
  
  @RequiredArgsConstructor
  private static class NodeActivation implements Serializable {
    private static final long serialVersionUID = 6132247629304903407L;
    
    final Node node;
    float activation;
    NodeActivation previous, next;
  }

  private final ConcurrentMap<Serializable, Node> index = new ConcurrentHashMap<>();
  private final Set<Node> nodes = Collections.newSetFromMap(new ConcurrentHashMap<>());
  private final ConcurrentMap<Node, NodeActivation> activations = new ConcurrentHashMap<>();
  private NodeActivation mostRecentActivation;
  
  private final transient PublishSubject<Node> nodeSeen = PublishSubject.create();
  private final transient Observable<Node> create = nodeSeen.filter(node -> !nodes.contains(node));
  
  public Observable<Node> create() {
    return create;
  }
  
  private final transient PublishSubject<Object> change = PublishSubject.create();
  
  public Observable<Object> change() {
    return change;
  }
  
  private void attachNodeSubscriber(final Node node) {
    node.setProperty().subscribe(e -> {
      nodeSeen.onNext(e.getProperty());
      nodeSeen.onNext(e.getValue());
      change.onNext(e);
    });
  }
  
  private void attachSubscribers() {
    create().subscribe(node -> {
      nodes.add(node);
      change.onNext(node);
      attachNodeSubscriber(node);
    });
  }
  
  {
    attachSubscribers();
  }
  

  private void readObject(final ObjectInputStream stream)
      throws IOException, ClassNotFoundException {
    stream.defaultReadObject();
    
    for (final Node node : nodes) {
      attachNodeSubscriber(node);
    }
    
    attachSubscribers();
  }

  public Node getOrCreateNode(final Serializable value) {
    return index.computeIfAbsent(value, value_ -> {
      final Node node = new Node(value_);
      nodeSeen.onNext(node);
      return node;
    });
  }
  
  public void activate(final Node node) {
    final NodeActivation activation = activations.computeIfAbsent(node, NodeActivation::new);
    if (activation.previous != null && activation.next != null) {
      activation.next.previous = activation.previous;
      activation.previous.next = activation.next;
    } else if (activation.next != null) {
      activation.next.previous = null;
    }
    
    activation.previous = mostRecentActivation;
    if (mostRecentActivation != null) {
      mostRecentActivation.next = activation;
    }
    activation.next = null;
    activation.activation = 1;
    
    mostRecentActivation = activation;
  }

  @Override
  public void close() {
    nodeSeen.onComplete();
    change.onComplete();
  }
}
