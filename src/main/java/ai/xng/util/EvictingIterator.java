package ai.xng.util;

import java.util.Iterator;

import lombok.val;

public abstract class EvictingIterator<T> implements Iterator<T> {
  private final Iterator<T> backing;
  private T next;

  public EvictingIterator(final Iterator<T> backing) {
    this.backing = backing;
    advance();
  }

  protected abstract boolean shouldEvict(T item);

  private void advance() {
    while (backing.hasNext()) {
      next = backing.next();
      if (shouldEvict(next)) {
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

  // remove is not straightforward since advancement happens after next to
  // determine hasNext.
}
