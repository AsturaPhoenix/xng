package ai.xng;

import java.util.Optional;

public abstract class InputNode implements Prior {
  private final Node.Trait node = new Node.Trait();
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
  public Connections.Posteriors getPosteriors() {
    return output.getPosteriors();
  }

  @Override
  public void activate() {
    node.activate();
    output.activate();
  }
}
