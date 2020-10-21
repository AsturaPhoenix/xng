package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import org.junit.jupiter.api.Test;

import lombok.val;

public class NodeTest {
  @Test
  public void testEmptySerialization() throws Exception {
    assertNotNull(TestUtil.serialize(new BiCluster().new Node()));
    assertNotNull(TestUtil.serialize(new ActionCluster().new Node(() -> {
    })));
    assertNotNull(TestUtil.serialize(new InputCluster().new Node()));
  }

  @Test
  public void testThen() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();
    val prior = new InputCluster().new Node();
    val posterior = TestUtil.testNode(new ActionCluster(), monitor);
    prior.then(posterior);
    prior.activate();
    scheduler.runUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testAnd() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();
    val input = new InputCluster(), output = new ActionCluster();
    val a = input.new Node(), b = input.new Node(), and = TestUtil.testNode(output, monitor);
    and.conjunction(a, b);
    a.activate();
    b.activate();
    scheduler.runUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testDisjointAnd() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();
    val input = new InputCluster(), output = new ActionCluster();
    val a = input.new Node(), b = input.new Node(), and = TestUtil.testNode(output, monitor);
    and.conjunction(a, b);

    a.activate();
    scheduler.runUntil(IntegrationProfile.TRANSIENT.period());
    b.activate();
    scheduler.runUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void test4And() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();
    val input = new InputCluster(), output = new ActionCluster();
    val a = input.new Node(), b = input.new Node(), c = input.new Node(), d = input.new Node(),
        and = TestUtil.testNode(output, monitor);
    and.conjunction(a, b, c, d);

    a.activate();
    b.activate();
    c.activate();
    d.activate();
    scheduler.runUntilIdle();
    assertTrue(monitor.didEmit());

    a.activate();
    b.activate();
    c.activate();
    scheduler.runUntilIdle();
    assertFalse(monitor.didEmit());
  }

  private static class AndFixture implements Serializable {
    private static final long serialVersionUID = 1L;

    transient EmissionMonitor<Long> monitor = new EmissionMonitor<>();
    final InputCluster input = new InputCluster();
    final ActionCluster output = new ActionCluster();
    final InputNode a = input.new Node(), b = input.new Node();
    final ActionCluster.Node and = output.new Node(this::onActivate);

    AndFixture() {
      and.conjunction(a, b);
    }

    void onActivate() {
      monitor.emit(Scheduler.global.now());
    }

    private void readObject(final ObjectInputStream stream) throws IOException, ClassNotFoundException {
      stream.defaultReadObject();
      monitor = new EmissionMonitor<>();
    }
  }

  @Test
  public void testAndSerialization() throws Exception {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val fixture = TestUtil.serialize(new AndFixture());

    fixture.a.activate();
    scheduler.runUntilIdle();
    assertFalse(fixture.monitor.didEmit());

    fixture.a.activate();
    fixture.b.activate();
    scheduler.runUntilIdle();
    assertTrue(fixture.monitor.didEmit());
  }

  /**
   * The refractory period of a neuron is the period after an activation where it
   * will not respond to another incoming pulse. This is implemented in the
   * threshold integrator, which only triggers on edges.
   *
   * Note that refractory periods do not apply to explicit {@link Node#activate()}
   * calls.
   */
  @Test
  public void testAndRefractory() throws InterruptedException {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();
    val input = new InputCluster(), output = new ActionCluster();
    val a = input.new Node(), b = input.new Node(), and = TestUtil.testNode(output, monitor);
    and.conjunction(a, b);
    a.activate();
    b.activate();
    b.activate();
    scheduler.runUntilIdle();
    assertThat(monitor.emissions()).hasSize(1);
  }

  @Test
  public void testNearCoincidentInhibition() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val monitor = new EmissionMonitor<Long>();
    val input = new InputCluster(), output = new ActionCluster();
    val up = input.new Node(), down = input.new Node(), out = TestUtil.testNode(output, monitor);
    up.then(out);
    down.inhibit(out);

    up.activate();
    down.activate();

    scheduler.runUntilIdle();
    assertFalse(monitor.didEmit());
  }

  /**
   * Posteriors should not hold strong references to priors since if the prior
   * cannot be activated, it should not have any effect.
   */
  @Test
  public void testGc() throws Exception {
    val input = new InputCluster();
    val posterior = new ActionCluster().new Node(() -> {
    });

    val gc = new GcFixture(posterior);

    for (int i = 0; i < 1000; ++i) {
      input.new Node().then(posterior);
    }

    gc.assertNoGrowth(() -> {
      System.gc();
      input.clean();
    });
  }
}
