package ai.xng.constructs;

import java.io.Serializable;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import ai.xng.ActionNode;
import ai.xng.InputCluster;
import ai.xng.InputCluster.Node;
import ai.xng.util.SerializableSupplier;
import ai.xng.util.Util;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Binary decoder for unicode code points ([0, 0x10FFFF], 21 bits).
 */
public class BinaryDecoder implements ActionNode.Action {
  public static record BitPair(InputCluster.Node lo, InputCluster.Node hi) implements Serializable {
  }

  public final SerializableSupplier<Integer> data;
  public final List<BitPair> output;

  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  public class OutputSet extends AbstractCollection<InputCluster.Node> {
    private final int value;

    @Override
    public Iterator<Node> iterator() {
      return new Iterator<InputCluster.Node>() {
        int i = 0;

        @Override
        public boolean hasNext() {
          return i < output.size();
        }

        @Override
        public Node next() {
          val bit = output.get(i);
          return (value >> i++ & 1) == 0 ? bit.lo() : bit.hi();
        }
      };
    }

    @Override
    public int size() {
      return output.size();
    }

    public OutputSet complement() {
      return new OutputSet(~value);
    }
  }

  public OutputSet outputFor(final int value) {
    if (value < 0 || value >= 1 << output.size()) {
      throw new IllegalArgumentException("Code point out of range.");
    }

    return new OutputSet(value);
  }

  public BinaryDecoder(final SerializableSupplier<Integer> data, final InputCluster output) {
    this.data = data;
    this.output = Collections.unmodifiableList(Arrays.asList(
        Util.generate(new BitPair[21], () -> new BitPair(output.new Node(), output.new Node()))));
  }

  @Override
  public void activate() {
    for (val node : outputFor(data.get())) {
      node.activate();
    }
  }
}
