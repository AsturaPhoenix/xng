package io.tqi.asn;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.EqualsAndHashCode;
import lombok.Value;

public class KnowledgeBase implements Serializable, AutoCloseable {
  @Value
  public static class AssociateEvent {
    Node parent, child;
  }
  
  public static class ContextUpdateEvent {
  }
  
  @Value
  @EqualsAndHashCode(callSuper = false)
  public static class ContextAdvanceEvent extends ContextUpdateEvent {
    long clock;
  }
  
  @Value
  @EqualsAndHashCode(callSuper = false)
  public static class ContextAugmentEvent extends ContextUpdateEvent {
    Node addedNode;
  }

  private static final long serialVersionUID = 4850129606513054849L;
  private static final int CONTEXT_TO_KEEP = 128;

  private final transient Subject<AssociateEvent> associate = PublishSubject.create();
  private final transient Subject<ContextUpdateEvent> contextUpdate = PublishSubject.create();

  private final ConcurrentMap<Serializable, Node> index = new ConcurrentHashMap<>();
  private long clock = Long.MIN_VALUE;
  private final ConcurrentLinkedDeque<Set<Node>> contextHistory = new ConcurrentLinkedDeque<>();

  public Observable<Object> changes() {
    return Observable.merge(associate, contextUpdate);
  }

  public void advanceContext() {
    clock++;
    contextHistory.addFirst(Collections.synchronizedSet(new HashSet<>()));
    while (contextHistory.size() > CONTEXT_TO_KEEP)
      contextHistory.removeLast();
    
    contextUpdate.onNext(new ContextAdvanceEvent(clock));
  }

  private Set<Node> context() {
    return contextHistory.getFirst();
  }
  
  private void augmentContext(final Node toFocus) {
    context().add(toFocus);
    contextUpdate.onNext(new ContextAugmentEvent(toFocus));
  }

  public void associate(final Serializable parentValue, final Optional<Serializable> relationValue,
      final Serializable childValue) {
    final Node parent = index.computeIfAbsent(parentValue, Node::new),
        child = index.computeIfAbsent(childValue, Node::new);
    final Optional<Node> relation = relationValue.map(rv -> index.computeIfAbsent(rv, Node::new));
    parent.associate(relation, child, clock);
    augmentContext(parent);
    relation.ifPresent(this::augmentContext);
    augmentContext(child);
    associate.onNext(new AssociateEvent(parent, child));
  }

  public void associate(final Serializable parentValue, final Serializable relationValue,
      final Serializable childValue) {
    associate(parentValue, Optional.ofNullable(relationValue), childValue);
  }

  @Override
  public void close() {
    associate.onComplete();
  }
}
