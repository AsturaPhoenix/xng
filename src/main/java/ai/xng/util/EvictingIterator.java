package ai.xng.util;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

public abstract class EvictingIterator<T> implements Iterator<T> {
  public static class Lambda<T> extends EvictingIterator<T> {
    private final Predicate<T> shouldEvict;

    public Lambda(final Iterator<T> backing, final Predicate<T> shouldEvict) {
      super(backing);
      this.shouldEvict = shouldEvict;
    }

    @Override
    protected boolean shouldEvict(final T item) {
      return shouldEvict.test(item);
    }
  }

  private final Iterator<T> backing;
  private T next;
  private boolean canRemove = false, needsAdvance = true, isTerminal = false;

  public EvictingIterator(final Iterator<T> backing) {
    this.backing = backing;
  }

  protected abstract boolean shouldEvict(T item);

  private void advance() {
    needsAdvance = false;
    while (backing.hasNext()) {
      next = backing.next();
      canRemove = false;
      if (shouldEvict(next)) {
        backing.remove();
      } else {
        return;
      }
    }

    isTerminal = true;
  }

  @Override
  public boolean hasNext() {
    if (needsAdvance) {
      advance();
    }
    return !isTerminal;
  }

  @Override
  public T next() {
    if (needsAdvance) {
      advance();
    }
    if (isTerminal) {
      throw new NoSuchElementException();
    }

    canRemove = true;
    needsAdvance = true;
    return next;
  }

  /**
   * This implementation is more restrictive than {@link Iterator#remove()} as it
   * may not be callable after {@link #hasNext()} if the underlying iterator has
   * been advanced due to element eviction.
   */
  @Override
  public void remove() {
    if (!canRemove) {
      throw new IllegalStateException();
    }

    backing.remove();
  }
}
