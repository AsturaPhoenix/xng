package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;

import com.google.common.collect.Iterables;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * A linked collection that allows its links to be promoted to head. This
 * collection is not thread-safe.
 */
public class RecencyQueue<T> implements Iterable<T>, Serializable {
  private static final long serialVersionUID = 6562230609701943835L;

  @RequiredArgsConstructor
  public class Link {
    @Getter
    private Link previous, next;
    private final T value;

    public T get() {
      return value;
    }

    public void promote() {
      if (head == this)
        return;

      remove();

      next = head;
      if (head != null) {
        head.previous = this;
      }
      head = this;
      if (tail == null) {
        tail = this;
      }

      version = null;
    }

    public void remove() {
      if (next == null && previous == null)
        return;

      if (previous == null) {
        head = next;
      } else {
        previous.next = next;
      }

      if (next == null) {
        tail = previous;
      } else {
        next.previous = previous;
      }

      next = previous = null;
      version = null;
    }
  }

  private class Iterator implements java.util.Iterator<T> {
    final Object version;
    Link nextLink;

    Iterator() {
      version = RecencyQueue.this.version;
      nextLink = head;
    }

    @Override
    public boolean hasNext() {
      return nextLink != null;
    }

    @Override
    public T next() {
      if (version != RecencyQueue.this.version) {
        throw new ConcurrentModificationException();
      }

      if (!hasNext())
        throw new NoSuchElementException();

      final T nextItem = nextLink.value;
      nextLink = nextLink.next;
      return nextItem;
    }
  }

  private transient Link head, tail;
  private transient Object version;

  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.defaultWriteObject();
    o.writeObject(Iterables.toArray(this, Object.class));
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    stream.defaultReadObject();
    for (final Object o : (Object[]) stream.readObject()) {
      @SuppressWarnings("unchecked")
      val link = new Link((T) o);
      if (head == null) {
        head = link;
      }
      if (tail != null) {
        tail.next = link;
        link.previous = tail;
      }
      tail = link;
    }
  }

  @Override
  public java.util.Iterator<T> iterator() {
    if (version == null) {
      version = new Object();
    }
    return new Iterator();
  }

  @Override
  public String toString() {
    return Iterables.toString(this);
  }
}
