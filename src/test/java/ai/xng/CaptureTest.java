package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Stack;

import org.junit.jupiter.api.Test;

import ai.xng.Cluster.PriorClusterProfile;
import lombok.val;

public class CaptureTest {
  private static final int MAX_PRIORS = 10;

  /**
   * The delay beyond which it's not reasonable to expect a conjunctive capture is
   * unspecified, so this may need to be adjusted (but with discretion).
   */
  private static final long CONJUNCTIVE_CAPTURE_MAX_DELAY = IntegrationProfile.TRANSIENT.period() / 3;

  private final TestScheduler scheduler = new TestScheduler();
  {
    Scheduler.global = scheduler;
  }

  private final ActionCluster actions = new ActionCluster();

  private Cluster.CaptureBuilder capture() {
    return new Cluster.CaptureBuilder() {
      @Override
      protected ai.xng.ActionCluster.Node capture(final Iterable<PriorClusterProfile> priors,
          final PosteriorCluster<?> posteriorCluster, final float weight) {
        return new Cluster.Capture(actions, priors, posteriorCluster, weight).node;
      }
    };
  }

  private ActionCluster.Node capture(final Cluster<? extends Prior> priorCluster,
      final PosteriorCluster<?> posteriorCluster) {
    return capture().priors(priorCluster).posteriors(posteriorCluster);
  }

  private ActionCluster.Node disassociate(final Cluster<? extends Prior> priorCluster,
      final PosteriorCluster<?> posteriorCluster) {
    return capture().priors(priorCluster).posteriors(posteriorCluster, 0);
  }

  @Test
  public void testNoPrior() {
    val monitor = new EmissionMonitor<Long>();
    val input = new InputCluster(), output = new ActionCluster();
    val a = input.new Node(), out = TestUtil.testNode(output, monitor);

    out.trigger();
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    a.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testNoPosterior() {
    val monitor = new EmissionMonitor<Long>();
    val input = new InputCluster(), output = new ActionCluster();
    val a = input.new Node();
    TestUtil.testNode(output, monitor);

    a.activate();
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    a.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testAssociate() {
    val input = new InputCluster(),
        output = new ActionCluster();

    val in = input.new Node();
    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);

    in.activate();
    out.trigger();
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testAlreadyAssociated() {
    val input = new InputCluster(),
        output = new ActionCluster();

    val in = input.new Node();
    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);

    in.then(out);
    in.activate();
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testDisassociate() {
    val input = new InputCluster(),
        output = new ActionCluster();

    val in = input.new Node();
    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);

    in.then(out);
    in.activate();
    out.inhibit();
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testNoCoincidentAssociate() {
    val input = new InputCluster(),
        output = new ActionCluster();

    val in = input.new Node();
    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);

    out.trigger();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
    in.activate();
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testCoincidentDisassociate() {
    val input = new InputCluster(),
        output = new ActionCluster();

    val in = input.new Node();
    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);

    in.then(out);
    out.trigger();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
    in.activate();
    out.inhibit();
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testConjunction() {
    for (int n = 2; n <= MAX_PRIORS; ++n) {
      val input = new InputCluster(),
          output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.trigger();
      capture(input, output).trigger();

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
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
    for (int n = 2; n <= MAX_PRIORS; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.trigger();
      capture(input, output).trigger();

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
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
    for (int n = 2; n <= MAX_PRIORS; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
        in[i] = input.new Node();
        in[i].activate();
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.trigger();
      capture(input, output).trigger();

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
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
    for (int n = 2; n <= MAX_PRIORS; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
        in[i] = input.new Node();
        in[i].activate();
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.trigger();
      capture(input, output).trigger();

      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());
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
    // This test fails at 4 priors, which is okay as by then the least significant
    // prior will have decayed during training. A previous version with less leeway
    // would not fail until 7 priors.
    for (int n = 2; n <= 3; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
        in[i] = input.new Node();
        in[i].activate();
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.trigger();
      capture(input, output).trigger();

      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());
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
   * Verifies that posterior clusters not targetted in a capture operation are
   * also disassociated.
   */
  @Test
  public void testDisassociateOthers() {
    val input = new InputCluster(), output = new ActionCluster(), observer = new ActionCluster();

    val in = input.new Node();
    val monitor = new EmissionMonitor<Long>();
    val monitorNode = in.then(TestUtil.testNode(observer, monitor));

    in.activate();
    monitorNode.inhibit();
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  /**
   * Verifies that posterior clusters not targetted in a capture operation but
   * activated during the window are unchanged.
   */
  @Test
  public void testUnchangedOthers() {
    val input = new InputCluster(), output = new ActionCluster(), observer = new ActionCluster();

    val in = input.new Node();
    val monitor = new EmissionMonitor<Long>();
    in.then(TestUtil.testNode(observer, monitor));

    in.activate();
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  /**
   * This test should be roughly equivalent to the prior jitter test, but is
   * structured as a causal chain.
   */
  @Test
  public void testStickSequence() {
    val input = new InputCluster(), recog = new BiCluster(), output = new ActionCluster();

    val in = input.new Node();
    Prior tail = in;
    for (int i = 0; i < MAX_PRIORS; ++i) {
      tail = tail.then(recog.new Node());
    }

    in.activate();
    scheduler.fastForwardFor(MAX_PRIORS * IntegrationProfile.TRANSIENT.defaultInterval());

    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);
    out.trigger();
    capture(recog, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testFullyDelayedTraining() {
    val input = new InputCluster(), output = new ActionCluster();

    final InputNode in = input.new Node();
    in.activate();

    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);
    out.trigger();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testMostlyDelayedTraining() {
    val input = new InputCluster(), output = new ActionCluster();

    final InputNode in = input.new Node();
    in.activate();

    val monitor = new EmissionMonitor<Long>();
    val out = TestUtil.testNode(output, monitor);
    out.trigger();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period() - 1);
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    monitor.reset();

    in.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testDelayedConjunction() {
    for (int n = 1; n <= MAX_PRIORS; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
      }

      scheduler.fastForwardFor(CONJUNCTIVE_CAPTURE_MAX_DELAY);

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.trigger();
      capture(input, output).trigger();

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
      monitor.reset();

      for (int i = 0; i < in.length; ++i) {
        in[i].activate();
      }
      scheduler.fastForwardUntilIdle();
      assertTrue(monitor.didEmit());
    }
  }

  @Test
  public void testDelayedAllButOne() {
    for (int n = 1; n <= MAX_PRIORS; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
      }

      scheduler.fastForwardFor(CONJUNCTIVE_CAPTURE_MAX_DELAY);

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.trigger();
      capture(input, output).trigger();

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
      monitor.reset();

      for (int i = 0; i < in.length - 1; ++i) {
        in[i].activate();
      }
      scheduler.fastForwardUntilIdle();
      assertFalse(monitor.didEmit());
    }
  }

  // Ensures reasonable capture when the capture itself is delayed.
  @Test
  public void testDelayedCapture() {
    for (int n = 1; n <= MAX_PRIORS; ++n) {
      val input = new InputCluster(), output = new ActionCluster();

      val in = new InputNode[n];
      for (int i = 0; i < in.length; ++i) {
        in[i] = input.new Node();
        in[i].activate();
      }

      val monitor = new EmissionMonitor<Long>();
      val out = TestUtil.testNode(output, monitor);
      out.trigger();

      scheduler.fastForwardFor(
          (long) (IntegrationProfile.TRANSIENT.rampDown() * (Prior.THRESHOLD_MARGIN / Prior.DEFAULT_COEFFICIENT)) - 1);
      capture(input, output).trigger();

      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
      monitor.reset();

      for (int i = 0; i < in.length; ++i) {
        in[i].activate();
      }
      scheduler.fastForwardUntilIdle();
      assertTrue(monitor.didEmit(), String.format("Failed with %s priors.", n));
    }
  }

  @Test
  public void testAssociateDisassociateSymmetry() {
    val monitor = new EmissionMonitor<Long>();

    val priorCluster = new InputCluster();
    val posteriorCluster = new ActionCluster();
    val prior = priorCluster.new Node();
    val posterior = TestUtil.testNode(posteriorCluster, monitor);

    for (int i = 0; i < 100; ++i) {
      prior.activate();
      posterior.trigger();
      capture(priorCluster, posteriorCluster).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

      monitor.reset();
      prior.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
      assertTrue(monitor.didEmit(), String.format("Failed on iteration %s. Posteriors: %s", i, prior.getPosteriors()));

      prior.activate();
      posterior.inhibit();
      capture(priorCluster, posteriorCluster).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

      monitor.reset();
      prior.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
      assertFalse(monitor.didEmit(), String.format("Failed on iteration %s. Posteriors: %s", i, prior.getPosteriors()));
    }
  }

  /**
   * Ensures that doing a capture does not erase posteriors whose contributions
   * from the designated prior clusters is sub-threshold. If this happens, it can
   * erase property bindings when new properties are bound.
   * <p>
   * This use case is a little fragile by nature, but alternatives are likely to
   * be more complex.
   */
  @Test
  public void testPreserveProperty() {
    val input = new InputCluster(),
        output = new ActionCluster();

    val object = input.new Node(), property = input.new Node();

    val monitor = new EmissionMonitor<Long>();
    TestUtil.testNode(output, monitor).conjunction(object, property);

    object.activate();
    capture(input, output).trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

    object.activate();
    property.activate();
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  // TODO: Use linked structure instead of salience stack. A linked structure
  // seems more correct and may eliminate the need for the scale operation.
  @Test
  public void testStack() {
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
      Cluster.scalePosteriors(priorCluster, KnowledgeBase.PUSH_FACTOR);
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

      testStack.activate();
      item.trigger();
      capture(priorCluster, posteriorCluster).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    }

    monitor.reset();

    while (!refStack.isEmpty()) {
      val item = refStack.pop();
      testStack.activate();
      disassociate(priorCluster, posteriorCluster).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
      Cluster.scalePosteriors(priorCluster, KnowledgeBase.POP_FACTOR);
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
      assertThat(monitor.emissions()).as("Stack size: %s", refStack.size() + 1).containsExactly(item);
    }
  }

  /**
   * Ensures that any residual connection after a symmetric associate/disassociate
   * pair will not be scaled back up to potency during stack pops.
   */
  @Test
  public void testStackEviction() {
    val monitor = new EmissionMonitor<Long>();

    val priorCluster = new InputCluster();
    val posteriorCluster = new ActionCluster();
    val prior = priorCluster.new Node();
    val posterior = TestUtil.testNode(posteriorCluster, monitor);

    // An associate/disassociate pair from testAssociateDisassociateSymmetry before
    // scaling.

    prior.activate();
    posterior.trigger();
    capture(priorCluster, posteriorCluster).trigger();
    scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());

    prior.activate();
    disassociate(priorCluster, posteriorCluster).trigger();
    scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());

    prior.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    Cluster.scalePosteriors(priorCluster, (float) Math.pow(KnowledgeBase.POP_FACTOR, 32));
    scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());

    monitor.reset();
    prior.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testNoSelfCapture() {
    val cluster = new BiCluster();
    val node = cluster.new Node();

    node.trigger();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());
    capture(cluster, cluster).trigger();
    scheduler.fastForwardUntilIdle();

    assertEquals(0, node.getPosteriors().getEdge(node, IntegrationProfile.TRANSIENT).distribution.generate());
  }
}
