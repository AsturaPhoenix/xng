package ai.xng;

import java.io.Serializable;

public record IntegrationProfile(long rampUp, long period) implements Serializable {
  private static final long serialVersionUID = 1L;

  public static final IntegrationProfile TRANSIENT = new IntegrationProfile(5, 50),
      PERSISTENT = new IntegrationProfile(50, 10000);

  public static IntegrationProfile fromEdges(long rampUp, long rampDown) {
    return new IntegrationProfile(rampUp, rampUp + rampDown);
  }

  public long rampDown() {
    return period - rampUp;
  }
}
