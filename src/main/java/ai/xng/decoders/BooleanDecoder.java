package ai.xng.decoders;

import java.io.Serializable;

import ai.xng.InputCluster;
import ai.xng.util.SerializableSupplier;

public class BooleanDecoder implements Serializable {
  private static final long serialVersionUID = 1L;

  public final SerializableSupplier<Boolean> data;
  public final InputCluster.Node isFalse, isTrue;

  public BooleanDecoder(final SerializableSupplier<Boolean> data, final InputCluster output) {
    this.data = data;
    isFalse = output.new Node();
    isTrue = output.new Node();
  }

  public void activate() {
    if (data.get()) {
      isTrue.activate();
    } else {
      isFalse.activate();
    }
  }
}
