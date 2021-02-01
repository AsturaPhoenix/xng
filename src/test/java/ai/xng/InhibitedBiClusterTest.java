package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import lombok.val;

public class InhibitedBiClusterTest {
  @Test
  public void test1Blocked() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new BiCluster();
    val bicluster = new InhibitedBiCluster(control.new Node());
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val stack = bicluster.input.new Node();
    stack.output.then(TestUtil.testNode(output, monitor));
    val runTest = control.new Node();
    runTest.then(stack, bicluster.inhibitor);

    runTest.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(bicluster.output.activations()).isEmpty();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void test1Passthrough() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new BiCluster();
    val bicluster = new InhibitedBiCluster(control.new Node());
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val stack = bicluster.input.new Node();
    stack.output.then(TestUtil.testNode(output, monitor));
    val runTest = control.new Node();
    runTest.then(stack);

    runTest.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(bicluster.output.activations()).containsExactly(stack.output);
    assertTrue(monitor.didEmit());
  }

  @Test
  public void test1DecayedInhibitor() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val control = new BiCluster();
    val bicluster = new InhibitedBiCluster(control.new Node());
    val output = new ActionCluster();
    val monitor = new EmissionMonitor<Long>();

    val stack = bicluster.input.new Node();
    stack.output.then(TestUtil.testNode(output, monitor));
    val invokeInhibitor = control.new Node(), invokeStack = control.new Node();
    invokeInhibitor.then(bicluster.inhibitor);
    invokeStack.then(stack);

    invokeInhibitor.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());
    invokeStack.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(bicluster.output.activations()).containsExactly(stack.output);
    assertTrue(monitor.didEmit());
  }
}
