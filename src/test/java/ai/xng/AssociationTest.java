package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Stack;

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
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
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
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
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

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
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

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
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
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
      Cluster.associate(input, output);

      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.rampDown());
      monitor.reset();

      for (val i : in) {
        i.activate();
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
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
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
      Cluster.associate(input, output);

      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.rampDown());
      monitor.reset();

      for (int i = 0; i < in.length - 1; ++i) {
        in[i].activate();
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
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
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
      Cluster.associate(input, output);

      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.rampDown());
      monitor.reset();

      for (int i = 1; i < in.length; ++i) {
        in[i].activate();
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
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
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
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

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());

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

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());

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

    for (int n = 1; n <= 10; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
      }

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak() + IntegrationProfile.TRANSIENT.period() / 3);

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
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

  @Test
  public void testAssociateDisassociateSymmetry() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();

    val priorCluster = new InputCluster();
    val posteriorCluster = new ActionCluster();
    val prior = priorCluster.new Node();
    val posterior = TestUtil.testNode(posteriorCluster, monitor);

    for (int i = 0; i < 100; ++i) {
      prior.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
      posterior.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
      Cluster.associate(priorCluster, posteriorCluster);
      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());

      monitor.reset();
      prior.activate();
      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());
      assertTrue(monitor.didEmit(),
          String.format("Failed on iteration %s. Posteriors: %s", i, prior.getPosteriors()));

      prior.activate();
      scheduler.fastForwardFor(2 * IntegrationProfile.TRANSIENT.defaultInterval());
      Cluster.disassociate(priorCluster, posteriorCluster);
      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());

      monitor.reset();
      prior.activate();
      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());
      assertFalse(monitor.didEmit(),
          String.format("Failed on iteration %s. Posteriors: %s", i, prior.getPosteriors()));
    }
  }

  @Test
  public void testStack() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val priorCluster = new InputCluster();
    val posteriorCluster = new SignalCluster();
    val testStack = priorCluster.new Node();
    val monitor = EmissionMonitor.fromObservable(posteriorCluster.rxActivations());
    val refStack = new Stack<SignalCluster.Node>();

    for (int i = 0; i < 32; ++i) {
      val item = posteriorCluster.new Node();
      refStack.push(item);

      testStack.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
      Cluster.scalePosteriors(priorCluster, LanguageBootstrap.STACK_FACTOR);
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

      testStack.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
      item.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
      Cluster.associate(priorCluster, posteriorCluster);
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    }

    monitor.reset();

    while (!refStack.isEmpty()) {
      val item = refStack.pop();
      testStack.activate();
      scheduler.fastForwardFor(2 * IntegrationProfile.TRANSIENT.defaultInterval());
      Cluster.scalePosteriors(priorCluster, 1 / LanguageBootstrap.STACK_FACTOR);
      Cluster.disassociate(priorCluster, posteriorCluster);
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
      assertThat(monitor.emissions()).containsExactly(item);
    }
  }

  /**
   * Ensures that any residual connection after a symmetric associate/disassociate
   * pair will not be scaled back up to potency during stack pops.
   */
  @Test
  public void testStackEviction() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();

    val priorCluster = new InputCluster();
    val posteriorCluster = new ActionCluster();
    val prior = priorCluster.new Node();
    val posterior = TestUtil.testNode(posteriorCluster, monitor);

    // An associate/disassociate pair from testAssociateDisassociateSymmetry before
    // scaling.

    prior.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
    posterior.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
    Cluster.associate(priorCluster, posteriorCluster);
    scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());

    prior.activate();
    scheduler.fastForwardFor(2 * IntegrationProfile.TRANSIENT.defaultInterval());
    Cluster.disassociate(priorCluster, posteriorCluster);
    scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());

    prior.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    Cluster.scalePosteriors(priorCluster, (float) Math.pow(1 / LanguageBootstrap.STACK_FACTOR, 32));
    scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());

    monitor.reset();
    prior.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }
}
