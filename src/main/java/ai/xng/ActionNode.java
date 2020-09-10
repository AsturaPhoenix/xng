package ai.xng;

import java.io.Serializable;

import lombok.RequiredArgsConstructor;

public abstract class ActionNode implements Posterior {
  private static final long serialVersionUID = 1L;

  private final Posterior.Trait input = new Posterior.Trait(this);

  @Override
  public ThresholdIntegrator getIntegrator() {
    return input.getIntegrator();
  }

  @RequiredArgsConstructor
  public static class Lambda extends ActionNode {
    public interface OnActivate extends Serializable {
      void run();
    }

    private static final long serialVersionUID = 1L;

    private final OnActivate onActivate;

    @Override
    public void activate() {
      onActivate.run();
    }
  }
}
