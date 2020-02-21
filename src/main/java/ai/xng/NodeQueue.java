package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.Iterables;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * A collection of nodes ordered by most recent activation. This collection is
 * thread-safe.
 * 
 * This collection used to hold weak references to its nodes. This behavior may
 * be reintroduced in the future.
 */
public class NodeQueue implements Iterable<Node>, Serializable {
  private static final long serialVersionUID = -2635392533122747827L;

  @RequiredArgsConstructor
  private class Link {
    Link previous, next;
    final Node node;
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
      try (val lock = new DebugLock(lock)) {
        if (version != NodeQueue.this.version) {
          throw new ConcurrentModificationException();
        }

        nextNode = nextLink.node;
        nextLink = nextLink.next;
      }

      return nextNode;
    }
  }

  private final Lock lock = new ReentrantLock();

  private final Context context;
  private transient Link head, tail;
  private transient Object version;

  private transient Subject<Node> rxActivate;

  public Observable<Node> rxActivate() {
    return rxActivate;
  }

  public NodeQueue(final Context context) {
    this.context = context;
    init();
  }

  private void init() {
    rxActivate = PublishSubject.create();
  }

  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.defaultWriteObject();
    try (val lock = new DebugLock(lock)) {
      for (final Node node : this) {
        o.writeObject(node);
      }
      o.writeObject(null);
    }
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    stream.defaultReadObject();
    init();
    Node node;
    while ((node = (Node) stream.readObject()) != null) {
      add(node);
    }
  }

  public Lock mutex() {
    return lock;
  }

  public void add(final Node node) {
    final Link link = new Link(node);
    initAtTail(link);

    node.rxActivate().subscribe(a -> {
      if (context == null || a.context == context) {
        promote(link);
        rxActivate.onNext(node);
      }
    });
  }

  public void addAll(final Collection<Node> nodes) {
    for (final Node node : nodes) {
      add(node);
    }
  }

  private void remove(final Link link) {
    try (val lock = new DebugLock(lock)) {
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
  }

  private void initAtTail(final Link link) {
    try (val lock = new DebugLock(lock)) {
      if (tail == null) {
        head = tail = link;
      } else {
        link.previous = tail;
        tail.next = link;
        tail = link;
      }

      version = null;
    }
  }

  private void promote(final Link link) {
    try (val lock = new DebugLock(lock)) {
      if (link == head)
        return;

      remove(link);

      link.previous = null;
      link.next = head;
      head.previous = link;
      head = link;

      version = null;
    }
  }

  /**
   * For thread safety, iteration should be done while a lock on {@link #mutex()}
   * is held.
   */
  @Override
  public Iterator<Node> iterator() {
    try (val lock = new DebugLock(lock)) {
      if (version == null) {
        version = new Object();
      }
      return new NodeQueueIterator();
    }
  }

  @Override
  public String toString() {
    try (val lock = new DebugLock(lock)) {
      return Iterables.toString(this);
    }
  }
}
