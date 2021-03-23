package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import lombok.val;

public class GatedBiClusterTest {
  @Test
  public void testEmptyGated() {
    val control = new ActionCluster();
    val gated = new GatedBiCluster(control);
    gated.gate.activate();
    assertThat(gated.output.activations()).isEmpty();
  }

  @Test
  public void test0Gated() {
    val control = new ActionCluster();
    val gated = new GatedBiCluster(control);
    gated.input.new Node();
    gated.gate.activate();
    assertThat(gated.output.activations()).isEmpty();
  }

  @Test
  public void test1Blocked() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new ActionCluster();
    val gated = new GatedBiCluster(control);
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val stack = gated.input.new Node();
    stack.output.then(TestUtil.testNode(output, monitor));

    stack.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(gated.output.activations()).isEmpty();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void test1Passthrough() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new ActionCluster();
    val input = new InputCluster();
    val gated = new GatedBiCluster(control);
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val gate = input.new Node();
    gate.then(gated.gate);
    gate.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.peak());

    val stack = gated.input.new Node();
    stack.output.then(TestUtil.testNode(output, monitor));

    stack.activate();
    assertThat(gated.output.activations()).containsExactly(stack.output);
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void test1Delayed() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new ActionCluster();
    val input = new InputCluster();
    val gated = new GatedBiCluster(control);
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val trigger = input.new Node();
    val stack = gated.input.new Node();
    trigger.then(stack);
    stack.output.then(TestUtil.testNode(output, monitor));
    trigger.then(new BiCluster().new Node())
        .then(gated.gate);

    trigger.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(gated.output.activations()).containsExactly(stack.output);
    assertTrue(monitor.didEmit());
  }

  @Test
  public void test1DecayedInput() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new ActionCluster();
    val input = new InputCluster();
    val gated = new GatedBiCluster(control);
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val gate = input.new Node();
    gate.then(gated.gate);
    gate.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

    val stack = gated.input.new Node();
    stack.output.then(TestUtil.testNode(output, monitor));

    stack.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(gated.output.activations()).isEmpty();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void test1DecayedGate() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new ActionCluster();
    val input = new InputCluster();
    val gated = new GatedBiCluster(control);
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val trigger = input.new Node();
    val stack = gated.input.new Node();
    trigger.then(stack);
    stack.output.then(TestUtil.testNode(output, monitor));

    trigger.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

    val gate = input.new Node();
    gate.then(gated.gate);
    gate.activate();

    scheduler.fastForwardUntilIdle();
    assertThat(gated.output.activations()).isEmpty();
    assertFalse(monitor.didEmit());
  }

  /**
   * Covers a bug where an output coincident with gate activation but processed
   * before (or in some cases even after) gate activation can be triggered twice.
   */
  @Test
  public void testNoDoubleActivation() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val executionCluster = new BiCluster();
    val actionCluster = new ActionCluster();
    val biCluster = new GatedBiCluster(actionCluster);

    val input = biCluster.input.new Node();

    val monitor = new EmissionMonitor<Long>();
    input.output.getPosteriors()
        .getEdge(TestUtil.testNode(actionCluster, monitor), IntegrationProfile.TRANSIENT).distribution.set(.8f);

    val trigger = executionCluster.new Node();
    // The ordering here is Heisenbuggy.
    trigger.then(biCluster.gate, input);
    trigger.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }
}
