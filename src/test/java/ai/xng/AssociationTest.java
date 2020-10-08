package ai.xng;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import lombok.val;

public class AssociationTest {
  @Test
  public void testOneToOne() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();
    val input = new InputCluster(), output = new ActionCluster();
    val a = input.new Node(), b = TestUtil.testNode(output, monitor);

    a.activate();
    scheduler.runFor(Prior.RAMP_UP);
    b.activate();
    scheduler.runFor(Prior.RAMP_UP);
    Cluster.associate(input, output);

    scheduler.runFor(Prior.RAMP_DOWN);
    monitor.reset();

    a.activate();
    scheduler.runUntilIdle();
    assertTrue(monitor.didEmit());
  }
}
