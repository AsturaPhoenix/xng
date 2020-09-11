package ai.xng;

import java.util.Map;

public class InputNode implements Prior {
  private static final long serialVersionUID = 1L;

  private final Prior.Trait output = new Prior.Trait();

  @Override
  public Map<Posterior, Profile> getPosteriors() {
    return output.getPosteriors();
  }

  @Override
  public void activate() {
    output.activate();
  }
}
