package ai.xng;

import java.io.Serializable;

import com.google.common.collect.ImmutableList;

public record IntegrationProfile(long delay, long peak, long period) implements Serializable {

  public static final IntegrationProfile TRANSIENT = new IntegrationProfile(0, 5, 50);
  public static final IntegrationProfile TWOGRAM = new IntegrationProfile(5, 50, 100);
  public static final IntegrationProfile PERSISTENT = new IntegrationProfile(50, 100, 3000);

  public static ImmutableList<IntegrationProfile> COMMON = ImmutableList.of(TRANSIENT, PERSISTENT);

  public static IntegrationProfile fromEdges(long rampUp, long rampDown) {
    return new IntegrationProfile(0, rampUp, rampUp + rampDown);
  }

  public long rampUp() {
    return peak - delay;
  }

  public long rampDown() {
    return period - peak;
  }

  /**
   * The time between prior and posterior activation for a solo connection under
   * {@link Prior#DEFAULT_COEFFICIENT}.
   */
  public long defaultInterval() {
    return delay + (long) Math.ceil(rampUp() / Prior.DEFAULT_COEFFICIENT);
  }
}
