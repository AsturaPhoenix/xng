package ai.xng.constructs;

import ai.xng.ActionNode;
import ai.xng.InputCluster;
import ai.xng.util.SerializableSupplier;

public class BooleanDecoder implements ActionNode.Action {
  public final SerializableSupplier<Boolean> data;
  public final InputCluster.Node isFalse, isTrue;

  public BooleanDecoder(final SerializableSupplier<Boolean> data, final InputCluster output) {
    this.data = data;
    isFalse = output.new Node();
    isTrue = output.new Node();
  }

  @Override
  public void activate() {
    if (data.get()) {
      isTrue.activate();
    } else {
      isFalse.activate();
    }
  }
}
