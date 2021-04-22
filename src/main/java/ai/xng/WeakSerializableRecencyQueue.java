package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;

import com.google.common.collect.Iterables;

import lombok.val;

/**
 * A specialization of {@link RecencyQueue} that holds weak references (evicted
 * on iteration and explicit cleanup).
 */
public class WeakSerializableRecencyQueue<T extends Serializable> implements Iterable<T>, Serializable {
  private static class WeakIterator<T> implements Iterator<T> {
    final Iterator<WeakReference<T>> backing;
    T next;

    WeakIterator(final Iterator<WeakReference<T>> backing) {
      this.backing = backing;
      advance();
    }

    private void advance() {
      while (backing.hasNext()) {
        next = backing.next().get();
        if (next == null) {
          backing.remove();
        } else {
          return;
        }
      }

      next = null;
    }

    @Override
    public boolean hasNext() {
      return next != null;
    }

    @Override
    public T next() {
      val next = this.next;
      advance();
      return next;
    }
  }

  private transient RecencyQueue<WeakReference<T>> backing;

  public class Link implements Serializable {
    private transient RecencyQueue<WeakReference<T>>.Link link;

    public T get() {
      return link.get().get();
    }

    public Link(final T value) {
      link = backing.new Link(new WeakReference<>(value));
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
      o.defaultWriteObject();
      o.writeObject(link.get().get());
    }

    private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
      o.defaultReadObject();
      link = backing.new Link(new WeakReference<>((T) o.readObject()));
    }

    public void promote() {
      link.promote();
    }
  }

  @Override
  public java.util.Iterator<T> iterator() {
    return new WeakIterator<>(backing.iterator());
  }

  public java.util.Iterator<T> reverseIterator() {
    return new WeakIterator<>(backing.reverseIterator());
  }

  public void clean() {
    // TODO: Guard against connected but rarely promoted links blocking GC by
    // thresholding this.
    val it = backing.reverseIterator();
    while (it.hasNext() && it.next().get() == null) {
      it.remove();
    }
  }

  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.defaultWriteObject();
    val list = new ArrayList<T>();
    for (val value : this) {
      list.add(value);
    }
    o.writeObject(list);
  }

  private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
    o.defaultReadObject();
    init();
    // Links will be re-added as they deserialize.
    o.readObject();
  }

  private void init() {
    backing = new RecencyQueue<>();
  }

  public WeakSerializableRecencyQueue() {
    init();
  }

  @Override
  public String toString() {
    return Iterables.toString(this);
  }
}
