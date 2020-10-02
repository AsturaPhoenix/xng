package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    val prior = new InputCluster().new Node();
    val posterior = new TestNode();
    prior.then(posterior);
    prior.activate();
    scheduler.runUntilIdle();
    assertTrue(posterior.didActivate());
  }

  @Test
  public void testAnd() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;
    val input = new InputCluster();
    val a = input.new Node(), b = input.new Node(), and = new TestNode();
    and.conjunction(a, b);
    a.activate();
    b.activate();
    scheduler.runUntilIdle();
    assertTrue(and.didActivate());
  }

  @Test
  public void testDisjointAnd() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;
    val input = new InputCluster();
    val a = input.new Node(), b = input.new Node(), and = new TestNode();
    and.conjunction(a, b);

    a.activate();
    scheduler.runUntil(Prior.RAMP_UP + Prior.RAMP_DOWN);
    b.activate();
    scheduler.runUntilIdle();
    assertFalse(and.didActivate());
  }

  @Test
  public void test4And() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster();
    val a = input.new Node(), b = input.new Node(), c = input.new Node(), d = input.new Node(), and = new TestNode();
    and.conjunction(a, b, c, d);

    {
      a.activate();
      b.activate();
      c.activate();
      d.activate();
      scheduler.runUntilIdle();
      assertTrue(and.didActivate());
    }

    and.reset();

    {
      a.activate();
      b.activate();
      c.activate();
      scheduler.runUntilIdle();
      assertFalse(and.didActivate());
    }
  }

  private static class AndFixture implements Serializable {
    private static final long serialVersionUID = 1L;

    final InputCluster input = new InputCluster();
    final InputNode a = input.new Node(), b = input.new Node();
    final TestNode and = new TestNode();

    AndFixture() {
      and.conjunction(a, b);
    }
  }

  @Test
  public void testAndSerialization() throws Exception {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val fixture = TestUtil.serialize(new AndFixture());

    fixture.a.activate();
    scheduler.runUntilIdle();
    assertFalse(fixture.and.didActivate());

    fixture.a.activate();
    fixture.b.activate();
    scheduler.runUntilIdle();
    assertTrue(fixture.and.didActivate());
  }

  /**
   * The refractory period of a neuron is the period after an activation where it
   * will not respond to another incoming pulse. This is implemented in the
   * synapse, which only triggers on edges.
   *
   * Note that refractory periods do not apply to explicit
   * {@link Node#activate(Context)} calls.
   */
  @Test
  public void testAndRefractory() throws InterruptedException {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster();
    val a = input.new Node(), b = input.new Node(), and = new TestNode();
    and.conjunction(a, b);
    a.activate();
    b.activate();
    b.activate();
    scheduler.runUntilIdle();
    assertThat(and.getActivations()).hasSize(1);
  }

  @Test
  public void testNearCoincidentInhibition() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val input = new InputCluster();
    val up = input.new Node(), down = input.new Node(), out = new TestNode();
    up.then(out);
    down.inhibit(out);

    up.activate();
    down.activate();

    scheduler.runUntilIdle();
    assertFalse(out.didActivate());
  }

  /**
   * Posteriors should not hold strong references to priors since if the prior
   * cannot be activated, it should not have any effect.
   */
  @Test
  public void testGc() throws Exception {
    val input = new InputCluster();
    val posterior = new TestNode();

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
