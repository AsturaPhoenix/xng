package ai.xng.constructs;

import java.io.Serializable;
import java.util.Optional;
import java.util.function.Function;

import ai.xng.ActionCluster;
import ai.xng.DataCluster;
import ai.xng.InputCluster;

public class BooleanDecoder implements Serializable {
  public final InputCluster.Node isFalse, isTrue;
  public final ActionCluster.Node node;

  public BooleanDecoder(final ActionCluster actionCluster, final DataCluster input, final InputCluster output,
      final Function<Object, Optional<Boolean>> extractor) {
    isFalse = output.new Node();
    isTrue = output.new Node();

    node = new CoincidentEffects(actionCluster).add(input,
        node -> extractor.apply(node.getData()).ifPresent(b -> {
          if (b) {
            isTrue.activate();
          } else {
            isFalse.activate();
          }
        })).node;
  }
}
