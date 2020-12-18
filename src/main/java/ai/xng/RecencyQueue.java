package ai.xng;

import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;

import com.google.common.collect.Iterables;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A linked collection that allows its links to be promoted to head. This
 * collection is not thread-safe.
 */
public class RecencyQueue<T> implements Iterable<T> {
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

  private abstract class Iterator implements java.util.Iterator<T> {
    final Object version;
    Link prevLink, nextLink;

    Iterator() {
      if (RecencyQueue.this.version == null) {
        RecencyQueue.this.version = new Object();
      }
      version = RecencyQueue.this.version;
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
      prevLink = nextLink;
      advance();
      return nextItem;
    }

    abstract void advance();

    @Override
    public void remove() {
      prevLink.remove();
      RecencyQueue.this.version = version;
    }
  }

  private Link head, tail;
  private Object version;

  @Override
  public java.util.Iterator<T> iterator() {
    return new Iterator() {
      {
        nextLink = head;
      }

      @Override
      void advance() {
        nextLink = nextLink.next;
      }
    };
  }

  public java.util.Iterator<T> reverseIterator() {
    return new Iterator() {
      {
        nextLink = tail;
      }

      @Override
      void advance() {
        nextLink = nextLink.previous;
      }
    };
  }

  @Override
  public String toString() {
    return Iterables.toString(this);
  }
}
