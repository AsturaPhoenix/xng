package ai.xng;

import java.util.Map;

public abstract class InputNode implements Prior {
  private static final long serialVersionUID = 1L;

  private final Prior.Trait output = new Prior.Trait();

  @Override
  public Map<Posterior, Distribution> getPosteriors() {
    return output.getPosteriors();
  }

  @Override
  public void activate() {
    output.activate();
  }
}
