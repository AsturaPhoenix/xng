package ai.xng;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ai.xng.util.Util;
import lombok.val;

public class BinaryDecoder implements Serializable {
  private static final long serialVersionUID = 1L;

  public static record BitPair(InputCluster.Node lo, InputCluster.Node hi) implements Serializable {
    private static final long serialVersionUID = 1L;
  }

  public final DataNode dataNode;
  public final List<BitPair> output;

  public BinaryDecoder(final DataNode dataNode, final InputCluster output) {
    this.dataNode = dataNode;
    this.output = Collections.unmodifiableList(Arrays.asList(
        Util.generate(new BitPair[16], () -> new BitPair(output.new Node(), output.new Node()))));
  }

  public void activate() {
    final short data = ((Number) dataNode.getData()).shortValue();
    for (int i = 0; i < output.size(); ++i) {
      val bit = output.get(i);
      if ((data >> i & 1) == 0) {
        bit.lo().activate();
      } else {
        bit.hi().activate();
      }
    }
  }
}
