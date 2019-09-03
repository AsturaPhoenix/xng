package ai.xng;

import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import com.google.common.collect.Iterables;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.Synchronized;

/**
 * A collection of nodes ordered by most recent activation. This collection
 * holds weak references to its nodes. This collection is thread-safe.
 */
public class NodeQueue implements Iterable<Node> {
  private class Link {
    Link previous, next;
    Node node;

    Link(final Node node) {
      this.node = node;
    }
  }

  private class NodeQueueIterator implements Iterator<Node> {
    final Object version;
    Link nextLink;

    NodeQueueIterator() {
      version = NodeQueue.this.version;
      nextLink = head;
    }

    @Override
    public boolean hasNext() {
      return nextLink != null;
    }

    @Override
    public Node next() {
      if (!hasNext())
        throw new NoSuchElementException();

      final Node nextNode;
      synchronized ($lock) {
        if (version != NodeQueue.this.version) {
          throw new ConcurrentModificationException();
        }

        nextNode = nextLink.node;
        nextLink = nextLink.next;
      }

      return nextNode;
    }
  }

  private Link head, tail;
  private Object version;

  private Subject<Node> rxActivate = PublishSubject.create();

  public Observable<Node> rxActivate() {
    return rxActivate;
  }

  public Object mutex() {
    return $lock;
  }

  public void add(final Node node) {
    final Link link = new Link(node);
    initAtTail(link);

    node.rxActivate().subscribe(t -> {
      promote(link);
      rxActivate.onNext(node);
    });
  }

  public void addAll(final Collection<Node> nodes) {
    for (final Node node : nodes) {
      add(node);
    }
  }

  @Synchronized
  private void remove(final Link link) {
    if (link.previous == null) {
      head = link.next;
    } else {
      link.previous.next = link.next;
    }

    if (link.next == null) {
      tail = link.previous;
    } else {
      link.next.previous = link.previous;
    }
    
    version = null;
  }

  @Synchronized
  private void initAtTail(final Link link) {
    if (tail == null) {
      head = tail = link;
    } else {
      link.previous = tail;
      tail.next = link;
      tail = link;
    }

    version = null;
  }

  @Synchronized
  private void promote(final Link link) {
    if (link == head)
      return;

    remove(link);

    link.previous = null;
    link.next = head;
    head.previous = link;
    head = link;

    version = null;
  }

  /**
   * For thread safety, iteration should be done while a lock on {@link #mutex()} is held.
   */
  @Synchronized
  @Override
  public Iterator<Node> iterator() {
    if (version == null) {
      version = new Object();
    }
    return new NodeQueueIterator();
  }

  @Synchronized
  @Override
  public String toString() {
    return Iterables.toString(this);
  }
}
