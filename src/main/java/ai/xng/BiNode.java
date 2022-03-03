package ai.xng;

import java.util.Optional;

public abstract class BiNode implements Prior, Posterior {
  private final Node.Trait node = new Node.Trait();
  private final Posterior.Trait input = new Posterior.Trait(this);
  private final Prior.Trait output = new Prior.Trait(this);

  @Override
  public LazyIntegrator getTrace() {
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
  public Connections.Priors getPriors() {
    return input.getPriors();
  }

  @Override
  public Connections.Posteriors getPosteriors() {
    return output.getPosteriors();
  }

  @Override
  public void activate() {
    node.activate();
    input.activate();
    output.activate();
  }

  @Override
  public BiNode conjunction(Prior... priors) {
    return (BiNode) Posterior.super.conjunction(priors);
  }

  @Override
  public BiNode inhibitor(Prior prior) {
    return (BiNode) Posterior.super.inhibitor(prior);
  }

  @Override
  public BiNode inhibitor(Prior prior, IntegrationProfile profile) {
    return (BiNode) Posterior.super.inhibitor(prior, profile);
  }
}
