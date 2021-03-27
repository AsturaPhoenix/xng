package ai.xng;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Serializable;

import org.junit.jupiter.api.Test;

import ai.xng.constructs.CoincidentEffect;
import lombok.val;

public class CoincidentEffectTest {
  private static class TestEffect implements Serializable {
    final BiCluster.Node trigger;
    final OutputNode input;
    int incidents = 0;

    TestEffect() {
      input = new SignalCluster().new Node();
      trigger = new BiCluster().new Node();
      trigger.then(new CoincidentEffect<OutputNode>(new ActionCluster(), input.getCluster()) {
        @Override
        protected void apply(final OutputNode node) {
          ++incidents;
        }
      }.node);
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
