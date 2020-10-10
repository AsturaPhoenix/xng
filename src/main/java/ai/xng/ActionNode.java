package ai.xng;

import java.io.Serializable;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public abstract class ActionNode extends OutputNode {
  private static final long serialVersionUID = 1L;

  /**
   * Convenience functional interface. Since each {@code ActionNode} is likely to
   * have a different {@code activate} implementation, this allows us to avoid
   * needing to declare serial version UIDs for all of them.
   */
  @FunctionalInterface
  public static interface OnActivate extends Serializable {
    void run();
  }

  private final OnActivate onActivate;

  @Override
  public void activate() {
    onActivate.run();
    super.activate();
  }
}
