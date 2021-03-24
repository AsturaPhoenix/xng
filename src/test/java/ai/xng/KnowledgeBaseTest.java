package ai.xng;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import lombok.val;

public class KnowledgeBaseTest {
  @Test
  public void testActionExceptionHandling() {
    try (val kb = new KnowledgeBase()) {
      val exception = new RuntimeException("foo");
      val thrower = kb.actions.new Node(() -> {
        throw exception;
      });
      thrower.activate();
      assertSame(exception, kb.lastException.getData());
    }
  }

  @Test
  public void testSuppressPosteriors() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    try (val kb = new KnowledgeBase()) {
      val prior = new StmCluster(kb.data);
      val monitor = new EmissionMonitor<Long>();
      val posterior = TestUtil.testNode(kb.actions, monitor);
      prior.address.then(posterior);

      val trigger = kb.execution.new Node();
      trigger.then(kb.suppressPosteriors, prior.address, prior.getClusterIdentifier());
      trigger.activate();
      scheduler.fastForwardUntilIdle();
      assertFalse(monitor.didEmit());
    }
  }

  /**
   * Ensures that if SuppressPosteriors is active but cluster selection and the
   * prior are not coincident, no suppression occurs.
   */
  @Test
  public void testSuppressPosteriorsNotSelected() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    try (val kb = new KnowledgeBase()) {
      val prior = new StmCluster(kb.data);
      val monitor = new EmissionMonitor<Long>();
      val posterior = TestUtil.testNode(kb.actions, monitor);
      prior.address.then(posterior);

      val trigger = kb.execution.new Node();
      trigger.then(kb.suppressPosteriors, prior.getClusterIdentifier());
      trigger.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

      trigger.getPosteriors().clear();
      trigger.then(kb.suppressPosteriors, prior.address);
      trigger.activate();
      scheduler.fastForwardUntilIdle();
      assertTrue(monitor.didEmit());
    }
  }
}
