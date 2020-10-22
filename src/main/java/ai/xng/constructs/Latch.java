package ai.xng.constructs;

import ai.xng.ActionCluster;
import ai.xng.ActionNode;
import ai.xng.InputCluster;

public class Latch implements ActionNode.Action {
  private static final long serialVersionUID = 1L;

  public final ActionCluster.Node set, clear;
  public final InputCluster.Node isTrue, isFalse;

  private boolean state;

  public Latch(final ActionCluster input, final InputCluster output) {
    set = input.new Node(() -> state = true);
    clear = input.new Node(() -> state = false);
    isTrue = output.new Node();
    isFalse = output.new Node();
  }

  @Override
  public void activate() {
    if (state) {
      isTrue.activate();
    } else {
      isFalse.activate();
    }
  }
}
