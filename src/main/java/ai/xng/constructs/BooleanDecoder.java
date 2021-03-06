package ai.xng.constructs;

import java.util.Optional;
import java.util.function.Function;

import ai.xng.ActionCluster;
import ai.xng.DataCluster;
import ai.xng.InputCluster;

public class BooleanDecoder extends Decoder {
  public final InputCluster.Node isFalse, isTrue;
  private final Function<Object, Optional<Boolean>> extractor;

  public BooleanDecoder(final ActionCluster actionCluster, final DataCluster input, final InputCluster output,
      Function<Object, Optional<Boolean>> extractor) {
    super(actionCluster, input);
    isFalse = output.new Node();
    isTrue = output.new Node();
    this.extractor = extractor;
  }

  @Override
  protected void decode(final Object data) {
    extractor.apply(data).ifPresent(b -> {
      if (b) {
        isTrue.activate();
      } else {
        isFalse.activate();
      }
    });
  }
}
