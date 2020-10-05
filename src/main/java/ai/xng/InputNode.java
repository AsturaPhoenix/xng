package ai.xng;

import java.util.Map;
import java.util.Optional;

public abstract class InputNode implements Prior {
  private static final long serialVersionUID = 1L;

  private final Node.Trait node = new Node.Trait();
  private final Prior.Trait output = new Prior.Trait();

  @Override
  public Optional<Long> getLastActivation() {
    return node.getLastActivation();
  }

  @Override
  public Map<Posterior, Distribution> getPosteriors() {
    return output.getPosteriors();
  }

  @Override
  public void activate() {
    node.activate();
    output.activate();
  }
}
