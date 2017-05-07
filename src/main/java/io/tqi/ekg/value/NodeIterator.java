package io.tqi.ekg.value;

import io.tqi.ekg.Node;

public class NodeIterator implements NodeValue {
  private static final long serialVersionUID = 9162602499893887554L;

  private final NodeList source;

  private int position = -1;

  public NodeIterator(final NodeList source) {
    this.source = source;
  }

  public boolean hasNext() {
    return position < source.size() - 1;
  }

  public boolean hasPrevious() {
    return position > 0;
  }

  public Node current() {
    return position >= 0 ? source.get(position) : null;
  }

  public Node next() {
    if (hasNext()) {
      position++;
    }
    return current();
  }

  public Node previous() {
    if (hasPrevious()) {
      position--;
    }
    return current();
  }

  public int getPosition() {
    return position;
  }

  @Override
  public String toString() {
    final Node current = current();
    return current == null || current.getValue() == null ? null : current.getValue().toString();
  }
}
