package ai.xng;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import lombok.val;

public class AssociationTest {
  @Test
  public void testNoPrior() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();
    val input = new InputCluster(), output = new ActionCluster();
    val a = input.new Node(), out = TestUtil.testNode(output, monitor);

    out.activate();
    scheduler.runFor(Prior.RAMP_UP);
    Cluster.associate(input, output);

    scheduler.runFor(Prior.RAMP_DOWN);
    monitor.reset();

    a.activate();
    scheduler.runUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testNoPosterior() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();
    val input = new InputCluster(), output = new ActionCluster();
    val a = input.new Node();
    TestUtil.testNode(output, monitor);

    a.activate();
    scheduler.runFor(Prior.RAMP_UP);
    Cluster.associate(input, output);

    scheduler.runFor(Prior.RAMP_DOWN);
    monitor.reset();

    a.activate();
    scheduler.runUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testConjunction() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    for (int n = 1; n <= 10; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
      }

      scheduler.runFor(Prior.RAMP_UP);

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.runFor(Prior.RAMP_UP);
      Cluster.associate(input, output);

      scheduler.runFor(Prior.RAMP_DOWN);
      monitor.reset();

      for (val i : in) {
        i.activate();
      }
      scheduler.runUntilIdle();
      assertTrue(monitor.didEmit());
    }
  }

  @Test
  public void testAllButOne() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    for (int n = 1; n <= 10; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
      }

      scheduler.runFor(Prior.RAMP_UP);

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.runFor(Prior.RAMP_UP);
      Cluster.associate(input, output);

      scheduler.runFor(Prior.RAMP_DOWN);
      monitor.reset();

      for (int i = 0; i < in.length - 1; ++i) {
        in[i].activate();
      }
      scheduler.runUntilIdle();
      assertFalse(monitor.didEmit());
    }
  }

  @Test
  public void testTestPriorJitter() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    for (int n = 1; n <= 10; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
        scheduler.runFor(Prior.RAMP_UP);
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.runFor(Prior.RAMP_UP);
      Cluster.associate(input, output);

      scheduler.runFor(Prior.RAMP_DOWN);
      monitor.reset();

      for (val i : in) {
        i.activate();
        scheduler.runFor(Prior.RAMP_UP);
      }
      scheduler.runUntilIdle();
      assertTrue(monitor.didEmit(), String.format("Failed with %s priors.", n));
    }
  }

  @Test
  public void testAllButOneJitter() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    for (int n = 1; n <= 10; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
        scheduler.runFor(Prior.RAMP_UP);
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.runFor(Prior.RAMP_UP);
      Cluster.associate(input, output);

      scheduler.runFor(Prior.RAMP_DOWN);
      monitor.reset();

      for (int i = 0; i < in.length - 1; ++i) {
        in[i].activate();
        scheduler.runFor(Prior.RAMP_UP);
      }
      scheduler.runUntilIdle();
      assertFalse(monitor.didEmit(), String.format("Failed with %s priors.", n));
    }
  }

  @Test
  public void testLeastSignificantOmitted() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    // This test fails at 7 priors, which is reasonable as by then the least
    // significant prior will have decayed greatly during training.
    for (int n = 1; n <= 6; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
        scheduler.runFor(Prior.RAMP_UP);
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.runFor(Prior.RAMP_UP);
      Cluster.associate(input, output);

      scheduler.runFor(Prior.RAMP_DOWN);
      monitor.reset();

      for (int i = 1; i < in.length; ++i) {
        in[i].activate();
        scheduler.runFor(Prior.RAMP_UP);
      }
      scheduler.runUntilIdle();
      assertFalse(monitor.didEmit(), String.format("Failed with %s priors.", n));
    }
  }

  /**
   * This test should be roughly equivalent to the prior jitter test, but is
   * structured as a causal chain.
   */
  @Test
  public void testStickSequence() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster(), recog = new BiCluster(), output = new ActionCluster();

    val in = input.new Node();
    Prior tail = in;
    for (int i = 0; i < 10; ++i) {
      tail = tail.then(recog.new Node());
    }

    in.activate();
    scheduler.runUntilIdle();

    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);
    out.activate();
    scheduler.runFor(Prior.RAMP_UP);
    Cluster.associate(recog, output);

    scheduler.runFor(Prior.RAMP_DOWN);
    monitor.reset();

    in.activate();
    scheduler.runUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testFullyDelayedTraining() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster(), output = new ActionCluster();

    final InputNode in = input.new Node();
    in.activate();

    scheduler.runFor(Prior.RAMP_UP);

    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);
    out.activate();
    scheduler.runFor(Prior.RAMP_UP + Prior.RAMP_DOWN);
    Cluster.associate(input, output);

    scheduler.runFor(Prior.RAMP_DOWN);
    monitor.reset();

    in.activate();
    scheduler.runUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testMostlyDelayedTraining() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster(), output = new ActionCluster();

    final InputNode in = input.new Node();
    in.activate();

    scheduler.runFor(Prior.RAMP_UP);

    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);
    out.activate();
    scheduler.runFor(Prior.RAMP_UP + Prior.RAMP_DOWN - 1);
    Cluster.associate(input, output);

    scheduler.runFor(Prior.RAMP_DOWN);
    monitor.reset();

    in.activate();
    scheduler.runUntilIdle();
    assertFalse(monitor.didEmit());
  }
}
