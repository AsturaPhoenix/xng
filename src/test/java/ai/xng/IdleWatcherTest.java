package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import ai.xng.constructs.IdleWatcher;
import lombok.val;

public class IdleWatcherTest {
  @Test
  public void testInactiveNone() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val output = new InputCluster();
    val monitor = TestUtil.activationTimeMonitor(output);
    new IdleWatcher(IntegrationProfile.TRANSIENT.period(), output, new InputCluster());

    scheduler.runUntilIdle();
    assertThat(monitor.emissions()).isEmpty();
  }

  @Test
  public void testInactiveOne() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster(), output = new InputCluster();
    val monitor = TestUtil.activationTimeMonitor(output);
    new IdleWatcher(IntegrationProfile.TRANSIENT.period(), output, input);

    input.new Node().activate();

    scheduler.runUntilIdle();
    assertThat(monitor.emissions()).isEmpty();
  }

  @Test
  public void testActiveNone() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val output = new InputCluster();
    val monitor = TestUtil.activationTimeMonitor(output);
    new IdleWatcher(IntegrationProfile.TRANSIENT.period(), output, new InputCluster()).activate();

    scheduler.runUntilIdle();
    assertThat(monitor.emissions()).containsExactly(IntegrationProfile.TRANSIENT.period());
  }

  @Test
  public void testActiveOneBeginning() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster(), output = new InputCluster();
    val monitor = TestUtil.activationTimeMonitor(output);
    new IdleWatcher(IntegrationProfile.TRANSIENT.period(), output, input).activate();

    input.new Node().activate();

    scheduler.runUntilIdle();
    assertThat(monitor.emissions()).containsExactly(IntegrationProfile.TRANSIENT.period());
  }

  @Test
  public void testActiveOneMiddle() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster(), output = new InputCluster();
    val monitor = TestUtil.activationTimeMonitor(output);
    val watcher = new IdleWatcher(IntegrationProfile.TRANSIENT.period(), output, input);
    watcher.activate();

    final long delay = watcher.period / 2;

    scheduler.runFor(delay);
    input.new Node().activate();

    scheduler.runUntilIdle();
    assertThat(monitor.emissions()).containsExactly(delay + watcher.period);
  }

  @Test
  public void testMixedMultiple() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster(), output = new InputCluster();
    val monitor = TestUtil.activationTimeMonitor(output);
    val watcher = new IdleWatcher(IntegrationProfile.TRANSIENT.period(), output, input);
    val inputNode = input.new Node();

    final long interval = watcher.period / 2;

    for (int i = 0; i < 2; ++i) {
      scheduler.runFor(interval);
      inputNode.activate();
    }
    watcher.activate();
    for (int i = 0; i < 2; ++i) {
      scheduler.runFor(interval);
      inputNode.activate();
    }

    scheduler.runUntilIdle();
    assertThat(monitor.emissions()).containsExactly(4 * interval + watcher.period);
  }

  @Test
  public void testMultipleActivations() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster(), output = new InputCluster();
    val monitor = TestUtil.activationTimeMonitor(output);
    val watcher = new IdleWatcher(IntegrationProfile.TRANSIENT.period(), output, input);
    val inputNode = input.new Node();

    final long interval = watcher.period / 2;

    watcher.activate();
    scheduler.runFor(interval);
    inputNode.activate();
    scheduler.runFor(3 * interval);
    inputNode.activate();
    scheduler.runFor(3 * interval);
    watcher.activate();
    scheduler.runFor(interval);
    inputNode.activate();
    scheduler.runUntilIdle();

    assertThat(monitor.emissions()).containsExactly(
        interval + watcher.period,
        8 * interval + watcher.period);
  }

  @Test
  public void testSerialization() throws Exception {
    TestUtil.serialize(new IdleWatcher(42, new InputCluster(), new BiCluster()));
  }
}
