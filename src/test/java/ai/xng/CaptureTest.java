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

  private ActionCluster.Node suppressPosteriors(final BiCluster cluster) {
    return Cluster.suppressPosteriors(actions, cluster);
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
   * Ensures that associativity-preserving property bindings can be set properly.
   * 
   * <p>
   * Naive direct "conjunctive" property bindings without an intermediate binding
   * layer do not preserve associativity, allowing unrelated keys to combine to
   * access bindings they should not. In Boolean logic terms, a key such as:
   * 
   * <pre>
   * A && B || C && D
   * </pre>
   * 
   * implemented naively as priors of weights ~ .5 ends up erroneously satisified
   * by A && C or B && D. As a concrete example, the bindings "Stephen is the son
   * of Carolyn" and "Stephen is the father of Reuben" end up erroneously
   * implying/encoding that "Stephen is the father of Carolyn" and "Stephen is the
   * son of Reuben".
   * 
   * <p>
   * The simplest way to solve this problem is to use separate nodes to encode the
   * conjunctions. To further simplify the implementation, we can make these nodes
   * throwaway, creating new ones for any property set operation and
   * disassociating old ones.
   * 
   * <p>
   * Seeing that this scheme preserves associativity is trivial. However, we
   * should test that setting such bindings works as expected.
   */
  @Test
  public void testBindingAssociativity() {
    val keys = new BiCluster(),
        binding = new BiCluster(),
        values = new ActionCluster();

    val object = keys.new Node(), property = keys.new Node();
    val oldMonitor = new EmissionMonitor<Long>(), newMonitor = new EmissionMonitor<Long>();
    val oldValue = TestUtil.testNode(values, oldMonitor), newValue = TestUtil.testNode(values, newMonitor);

    val captureConjunction = capture(keys, binding), captureDisjunction = capture(binding, values);

    // set old value
    object.trigger();
    property.trigger();
    suppressPosteriors(keys).trigger(); // (no effect, but to be consistent with the next set operation)
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
    binding.new Node().trigger();
    captureConjunction.trigger();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
    oldValue.trigger();
    captureDisjunction.trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    oldMonitor.reset();

    // verify old value
    object.trigger();
    property.trigger();
    scheduler.fastForwardFor(2 * IntegrationProfile.TRANSIENT.period());
    assertTrue(oldMonitor.didEmit());

    // set new value
    object.trigger();
    property.trigger();
    suppressPosteriors(keys).trigger();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
    binding.new Node().trigger();
    captureConjunction.trigger();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
    newValue.trigger();
    captureDisjunction.trigger();

    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    newMonitor.reset();

    // verify new value
    object.trigger();
    property.trigger();
    scheduler.fastForwardFor(2 * IntegrationProfile.TRANSIENT.period());
    assertFalse(oldMonitor.didEmit());
    assertTrue(newMonitor.didEmit());
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

  /**
   * Demonstrates stack behavior using a linked structure.
   */
  @Test
  public void testStack() {
    val testStack = new StmCluster("testStack");
    val parent = new StmCluster("parent"); // In practice, this would be a naming BiCluster.
    val bindings = new BiCluster(); // Not necessary for this simple case, but we want to exercise more realistic
                                    // property binding.
    val items = new BiCluster("items");
    val tmp = new StmCluster("tmp");
    val monitor = EmissionMonitor.fromObservable(items.rxActivations());
    val refStack = new Stack<BiCluster.Node>();

    for (int i = 0; i < 10; ++i) {
      val item = items.new Node(Integer.toString(i));
      refStack.push(item);

      // tmp = stack
      testStack.address.trigger();
      tmp.address.trigger();
      suppressPosteriors(tmp).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());

      capture(tmp, items).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

      // stack = item
      testStack.address.trigger();
      suppressPosteriors(testStack).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());

      item.trigger();
      capture(testStack, items).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

      // item.parent = tmp
      testStack.address.trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());

      parent.address.trigger();
      suppressPosteriors(items).trigger();
      suppressPosteriors(parent).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());

      bindings.new Node().trigger();
      capture().priors(items).priors(parent).posteriors(bindings).trigger();

      tmp.address.trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
      capture().priors(bindings).posteriors(items).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    }

    while (!refStack.isEmpty()) {
      monitor.reset();
      val item = refStack.pop();
      testStack.address.activate();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
      assertThat(monitor.emissions()).as("Stack size: %s", refStack.size() + 1).containsExactly(item);

      // tmp = stack.parent
      testStack.address.trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());
      parent.address.trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());

      tmp.address.trigger();
      suppressPosteriors(tmp).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());

      capture(tmp, items).trigger();
      suppressPosteriors(items).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

      // stack = tmp
      testStack.address.trigger();
      suppressPosteriors(testStack).trigger();
      tmp.address.trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.defaultInterval());

      capture(testStack, items).trigger();
      scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    }

    monitor.reset();
    testStack.address.activate();
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
