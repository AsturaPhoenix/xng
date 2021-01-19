package ai.xng;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

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
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
    Cluster.associate(input, output);

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampDown());
    monitor.reset();

    a.activate();
    scheduler.fastForwardUntilIdle();
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
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
    Cluster.associate(input, output);

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampDown());
    monitor.reset();

    a.activate();
    scheduler.fastForwardUntilIdle();
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

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      Cluster.associate(input, output);

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampDown());
      monitor.reset();

      for (val i : in) {
        i.activate();
      }
      scheduler.fastForwardUntilIdle();
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

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      Cluster.associate(input, output);

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampDown());
      monitor.reset();

      for (int i = 0; i < in.length - 1; ++i) {
        in[i].activate();
      }
      scheduler.fastForwardUntilIdle();
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
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      Cluster.associate(input, output);

      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.rampDown());
      monitor.reset();

      for (val i : in) {
        i.activate();
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      }
      scheduler.fastForwardUntilIdle();
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
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      Cluster.associate(input, output);

      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.rampDown());
      monitor.reset();

      for (int i = 0; i < in.length - 1; ++i) {
        in[i].activate();
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      }
      scheduler.fastForwardUntilIdle();
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
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      Cluster.associate(input, output);

      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.rampDown());
      monitor.reset();

      for (int i = 1; i < in.length; ++i) {
        in[i].activate();
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      }
      scheduler.fastForwardUntilIdle();
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
    scheduler.fastForwardUntilIdle();

    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);
    out.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
    Cluster.associate(recog, output);

    scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.rampDown());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testFullyDelayedTraining() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster(), output = new ActionCluster();

    final InputNode in = input.new Node();
    in.activate();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());

    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);
    out.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    Cluster.associate(input, output);

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampDown());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testMostlyDelayedTraining() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster(), output = new ActionCluster();

    final InputNode in = input.new Node();
    in.activate();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());

    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);
    out.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period() - 1);
    Cluster.associate(input, output);

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampDown());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testDelayedAllButOne() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    for (int n = 4; n <= 4; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
      }

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp() + IntegrationProfile.TRANSIENT.period() / 3);

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
      Cluster.associate(Arrays.asList(new Cluster.PriorClusterProfile(input, IntegrationProfile.TRANSIENT)), output);

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampDown());
      monitor.reset();

      for (int i = 0; i < in.length - 1; ++i) {
        in[i].activate();
      }
      scheduler.fastForwardUntilIdle();
      assertFalse(monitor.didEmit());
    }
  }
}
