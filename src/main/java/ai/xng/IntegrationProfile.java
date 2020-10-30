package ai.xng;

import java.io.Serializable;

import com.google.common.collect.ImmutableList;

public record IntegrationProfile(long rampUp, long period) implements Serializable {
  private static final long serialVersionUID = 1L;

  /**
   * 5/50 ms cycle
   */
  public static final IntegrationProfile TRANSIENT = new IntegrationProfile(5, 50);

  /**
   * 50ms/10s cycle
   */
  public static final IntegrationProfile PERSISTENT = new IntegrationProfile(50, 10000);

  public static ImmutableList<IntegrationProfile> COMMON = ImmutableList.of(TRANSIENT, PERSISTENT);

  public static IntegrationProfile fromEdges(long rampUp, long rampDown) {
    return new IntegrationProfile(rampUp, rampUp + rampDown);
  }

  public long rampDown() {
    return period - rampUp;
  }

  /**
   * The time between prior and posterior activation for a solo connection under
   * {@link Prior#DEFAULT_COEFFICIENT}.
   */
  public long defaultDelay() {
    return (long) Math.ceil(rampUp / Prior.DEFAULT_COEFFICIENT);
  }
}
