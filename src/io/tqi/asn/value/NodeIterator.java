package io.tqi.asn.value;

import java.io.Serializable;
import java.util.ArrayList;

import io.tqi.asn.Node;

public class NodeIterator implements Serializable {
  private static final long serialVersionUID = 9162602499893887554L;

  private final ArrayList<Node> source;

  private int position = -1;

  public NodeIterator(final ArrayList<Node> source) {
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
}
