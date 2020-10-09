package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

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
    integrator.add(1, 1, 1);
    scheduler.runUntilIdle();
    assertThat(output).containsExactly(1L);
  }

  @Test
  public void testTwoSimultaneous() {
    integrator.add(INTERVAL, INTERVAL, 1);
    integrator.add(INTERVAL, INTERVAL, 1);
    scheduler.runUntilIdle();
    assertThat(output).containsExactly(INTERVAL / 2);
  }

  @Test
  public void testConjunction() {
    integrator.add(INTERVAL, 2 * INTERVAL, .5f);
    scheduler.runUntil(INTERVAL);
    integrator.add(INTERVAL, 2 * INTERVAL, .75f);
    scheduler.runUntilIdle();
    assertThat(output).containsExactly(2 * INTERVAL);
  }

  @Test
  public void testInhibition() {
    integrator.add(INTERVAL, INTERVAL, 1);
    scheduler.runUntil(INTERVAL / 2);
    integrator.add(INTERVAL, INTERVAL, -1);
    scheduler.runUntilIdle();
    assertThat(output).isEmpty();
  }

  @Test
  public void testPullDown() {
    integrator.add(INTERVAL, INTERVAL, 2);
    scheduler.runUntil(INTERVAL);
    integrator.add(INTERVAL / 4, INTERVAL / 4, -1);
    scheduler.runUntilIdle();
    assertThat(output).containsExactly(INTERVAL / 2, 3 * INTERVAL / 2);
  }
}
