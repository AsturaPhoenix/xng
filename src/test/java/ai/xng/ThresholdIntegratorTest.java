package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import lombok.val;

public class ThresholdIntegratorTest {
  private static final long INTERVAL = 100;

  private final TestScheduler scheduler = new TestScheduler();
  private final List<Long> output = new ArrayList<>();
  private final ThresholdIntegrator integrator = new ThresholdIntegrator() {
    @Override
    protected void onThreshold() {
      output.add(scheduler.now());
    }
  };

  private ThresholdIntegratorTest() {
    Scheduler.global = scheduler;
  }

  @Test
  public void testShortPulse() {
    integrator.add(IntegrationProfile.fromEdges(1, 1), 1);
    scheduler.fastForwardUntilIdle();
    assertThat(output).containsExactly(1L);
  }

  @Test
  public void testTwoSimultaneous() {
    val profile = IntegrationProfile.fromEdges(INTERVAL, INTERVAL);
    integrator.add(profile, 1);
    integrator.add(profile, 1);
    scheduler.fastForwardUntilIdle();
    assertThat(output).containsExactly(INTERVAL / 2);
  }

  @Test
  public void testConjunction() {
    val profile = IntegrationProfile.fromEdges(INTERVAL, 2 * INTERVAL);
    integrator.add(profile, .5f);
    scheduler.fastForwardUntil(INTERVAL);
    integrator.add(profile, .75f);
    scheduler.fastForwardUntilIdle();
    assertThat(output).containsExactly(2 * INTERVAL);
  }

  @Test
  public void testInhibition() {
    val profile = IntegrationProfile.fromEdges(INTERVAL, INTERVAL);
    integrator.add(profile, 1);
    scheduler.fastForwardUntil(INTERVAL / 2);
    integrator.add(profile, -1);
    scheduler.fastForwardUntilIdle();
    assertThat(output).isEmpty();
  }

  @Test
  public void testPullDown() {
    integrator.add(IntegrationProfile.fromEdges(INTERVAL, INTERVAL), 2);
    scheduler.fastForwardUntil(INTERVAL);
    integrator.add(IntegrationProfile.fromEdges(INTERVAL / 4, INTERVAL / 4), -1);
    scheduler.fastForwardUntilIdle();
    assertThat(output).containsExactly(INTERVAL / 2, 3 * INTERVAL / 2);
  }

  @Test
  public void testAdjust() {
    val spike = integrator.add(IntegrationProfile.fromEdges(INTERVAL, INTERVAL), 1);
    spike.adjustRampUp(.75f / INTERVAL);
    scheduler.fastForwardUntil(INTERVAL);
    assertEquals(.75f, integrator.getNormalizedCappedValue());
  }

  @Test
  public void testAdjustAtPeak() {
    val spike = integrator.add(IntegrationProfile.fromEdges(INTERVAL, INTERVAL), 1);
    scheduler.fastForwardUntil(INTERVAL);
    spike.adjustRampUp(.75f / INTERVAL);
    assertEquals(1, integrator.getNormalizedCappedValue());
  }

  @Test
  public void testAdjustBeforeCurve() {
    val spike = integrator.add(new IntegrationProfile(INTERVAL, 2 * INTERVAL, 3 * INTERVAL), 1);
    spike.adjustRampUp(.75f / INTERVAL);
    scheduler.fastForwardFor(INTERVAL);
    assertEquals(0, integrator.getNormalizedCappedValue());
    scheduler.fastForwardFor(INTERVAL);
    assertEquals(.75f, integrator.getNormalizedCappedValue());
  }

  /**
   * Exercises a previous failure case where an update just as the integrator
   * crosses the threshold but before the scheduled handler can execute will clear
   * the handler and not schedule a new one.
   */
  @Test
  public void testUpdateAtThreshold() {
    val profile = IntegrationProfile.fromEdges(INTERVAL, INTERVAL);
    scheduler.postTask(() -> integrator.add(profile, 0), INTERVAL);
    integrator.add(profile, 1);
    scheduler.fastForwardUntilIdle();
    assertThat(output).containsExactly(INTERVAL);
  }
}
