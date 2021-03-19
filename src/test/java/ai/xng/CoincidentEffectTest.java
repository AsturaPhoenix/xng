package ai.xng;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ai.xng.constructs.CoincidentEffect;
import lombok.val;

public class CoincidentEffectTest {
  private static class TestEffect extends CoincidentEffect<Posterior> {
    final BiCluster.Node trigger;
    final OutputNode input;
    int incidents = 0;

    TestEffect() {
      super(new ActionCluster());
      trigger = new BiCluster().new Node();
      trigger.then(node);
      input = new SignalCluster().new Node();
      addCluster(input.getCluster());
    }

    @Override
    public void apply(final Posterior node) {
      ++incidents;
    }
  }

  @Test
  public void testCoincident() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val effect = new TestEffect();
    effect.trigger.then(effect.input);
    effect.trigger.activate();
    scheduler.fastForwardUntilIdle();
    assertEquals(1, effect.incidents);
  }

  /**
   * Covers a bug where a node that had recently been activated could be decoded
   * twice.
   */
  @Test
  public void testRecentlyActivatedData() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val effect = new TestEffect();
    effect.input.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

    effect.trigger.then(effect.input);
    effect.trigger.activate();
    scheduler.fastForwardUntilIdle();
    assertEquals(1, effect.incidents);
  }

  @Test
  public void testDataAfterTrigger() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val effect = new TestEffect();
    effect.trigger.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());

    effect.input.activate();
    scheduler.fastForwardUntilIdle();
    assertEquals(1, effect.incidents);
  }

  @Test
  public void testSerialization() throws Exception {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val effect = TestUtil.serialize(new TestEffect());
    effect.trigger.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());

    effect.input.activate();
    scheduler.fastForwardUntilIdle();
    assertEquals(1, effect.incidents);
  }

  @Test
  public void testDataAfterWindow() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val effect = new TestEffect();
    effect.trigger.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

    effect.input.activate();
    scheduler.fastForwardUntilIdle();
    assertEquals(0, effect.incidents);
  }
}
