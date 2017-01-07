package io.tqi.asn;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;

public class KnowledgeBase implements Serializable, AutoCloseable {
  private static final long serialVersionUID = 4850129606513054849L;

  private final ConcurrentMap<Serializable, Node> index = new ConcurrentHashMap<>();
  
  private final PublishSubject<Node> nodeSeen = PublishSubject.create();
  
  public Observable<Node> create() {
    return nodeSeen.distinct();
  }
  
  private final PublishSubject<Object> change = PublishSubject.create();
  
  public Observable<Object> change() {
    return change;
  }
  
  {
    create().subscribe(node -> {
      change.onNext(node);
      node.setProperty().subscribe(e -> {
        nodeSeen.onNext(e.getProperty());
        nodeSeen.onNext(e.getValue());
        change.onNext(e);
      });
    });
  }

  public Node getOrCreateNode(final Serializable value) {
    return index.computeIfAbsent(value, value_ -> {
      final Node node = new Node(value_);
      nodeSeen.onNext(node);
      return node;
    });
  }

  @Override
  public void close() {
    nodeSeen.onComplete();
    change.onComplete();
  }
}
