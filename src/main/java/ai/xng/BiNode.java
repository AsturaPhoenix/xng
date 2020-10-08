package ai.xng;

import java.util.Map;
import java.util.Optional;

public abstract class BiNode implements Prior, Posterior {
  private static final long serialVersionUID = 1L;

  private final Node.Trait node = new Node.Trait();
  private final Posterior.Trait input = new Posterior.Trait(this);
  private final Prior.Trait output = new Prior.Trait();

  @Override
  public Integrator getTrace() {
    return node.getTrace();
  }

  @Override
  public Optional<Long> getLastActivation() {
    return node.getLastActivation();
  }

  @Override
  public ThresholdIntegrator getIntegrator() {
    return input.getIntegrator();
  }

  @Override
  public Map<Prior, Distribution> getPriors() {
    return input.getPriors();
  }

  @Override
  public Map<Posterior, Distribution> getPosteriors() {
    return output.getPosteriors();
  }

  @Override
  public void activate() {
    node.activate();
    input.activate();
    output.activate();
  }
}
