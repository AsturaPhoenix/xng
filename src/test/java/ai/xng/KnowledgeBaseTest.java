package ai.xng;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

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
      trigger.then(kb.suppressPosteriors(prior), prior.address);
      trigger.activate();
      scheduler.fastForwardUntilIdle();
      assertFalse(monitor.didEmit());
    }
  }
}
