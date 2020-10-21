package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;

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
    scheduler.runUntilIdle();
    assertThat(output).containsExactly(1L);
  }

  @Test
  public void testTwoSimultaneous() {
    val profile = IntegrationProfile.fromEdges(INTERVAL, INTERVAL);
    integrator.add(profile, 1);
    integrator.add(profile, 1);
    scheduler.runUntilIdle();
    assertThat(output).containsExactly(INTERVAL / 2);
  }

  @Test
  public void testConjunction() {
    val profile = IntegrationProfile.fromEdges(INTERVAL, 2 * INTERVAL);
    integrator.add(profile, .5f);
    scheduler.runUntil(INTERVAL);
    integrator.add(profile, .75f);
    scheduler.runUntilIdle();
    assertThat(output).containsExactly(2 * INTERVAL);
  }

  @Test
  public void testInhibition() {
    val profile = IntegrationProfile.fromEdges(INTERVAL, INTERVAL);
    integrator.add(profile, 1);
    scheduler.runUntil(INTERVAL / 2);
    integrator.add(profile, -1);
    scheduler.runUntilIdle();
    assertThat(output).isEmpty();
  }

  @Test
  public void testPullDown() {
    integrator.add(IntegrationProfile.fromEdges(INTERVAL, INTERVAL), 2);
    scheduler.runUntil(INTERVAL);
    integrator.add(IntegrationProfile.fromEdges(INTERVAL / 4, INTERVAL / 4), -1);
    scheduler.runUntilIdle();
    assertThat(output).containsExactly(INTERVAL / 2, 3 * INTERVAL / 2);
  }
}
