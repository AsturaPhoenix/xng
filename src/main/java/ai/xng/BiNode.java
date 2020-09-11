package ai.xng;

import java.util.Map;

public class BiNode implements Prior, Posterior {
  private static final long serialVersionUID = 1L;

  private final Posterior.Trait input = new Posterior.Trait(this);
  private final Prior.Trait output = new Prior.Trait();

  @Override
  public ThresholdIntegrator getIntegrator() {
    return input.getIntegrator();
  }

  @Override
  public Map<Posterior, Profile> getPosteriors() {
    return output.getPosteriors();
  }

  @Override
  public void activate() {
    output.activate();
  }
}
