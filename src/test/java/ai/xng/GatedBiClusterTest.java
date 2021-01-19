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
    val bicluster = new GatedBiCluster(control);
    bicluster.gate.activate();
    assertThat(bicluster.output.activations()).isEmpty();
  }

  @Test
  public void test0Gated() {
    val control = new ActionCluster();
    val bicluster = new GatedBiCluster(control);
    bicluster.input.new Node();
    bicluster.gate.activate();
    assertThat(bicluster.output.activations()).isEmpty();
  }

  @Test
  public void test1Blocked() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new ActionCluster();
    val bicluster = new GatedBiCluster(control);
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val stack = bicluster.input.new Node();
    stack.output.then(TestUtil.testNode(output, monitor));

    stack.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(bicluster.output.activations()).isEmpty();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void test1Passthrough() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new ActionCluster();
    val input = new InputCluster();
    val bicluster = new GatedBiCluster(control);
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val gate = input.new Node();
    gate.then(bicluster.gate);
    gate.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());

    val stack = bicluster.input.new Node();
    stack.output.then(TestUtil.testNode(output, monitor));

    stack.activate();
    assertThat(bicluster.output.activations()).containsExactly(stack.output);
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void test1Delayed() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new ActionCluster();
    val input = new InputCluster();
    val bicluster = new GatedBiCluster(control);
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val trigger = input.new Node();
    val stack = bicluster.input.new Node();
    trigger.then(stack);
    stack.output.then(TestUtil.testNode(output, monitor));

    trigger.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.rampUp());

    bicluster.gate.activate();
    assertThat(bicluster.output.activations()).containsExactly(stack.output);
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void test1DecayedInput() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new ActionCluster();
    val input = new InputCluster();
    val bicluster = new GatedBiCluster(control);
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val gate = input.new Node();
    gate.then(bicluster.gate);
    gate.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

    val stack = bicluster.input.new Node();
    stack.output.then(TestUtil.testNode(output, monitor));

    stack.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(bicluster.output.activations()).isEmpty();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void test1DecayedGate() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new ActionCluster();
    val input = new InputCluster();
    val bicluster = new GatedBiCluster(control);
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val trigger = input.new Node();
    val stack = bicluster.input.new Node();
    trigger.then(stack);
    stack.output.then(TestUtil.testNode(output, monitor));

    trigger.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

    bicluster.gate.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(bicluster.output.activations()).isEmpty();
    assertFalse(monitor.didEmit());
  }
}
