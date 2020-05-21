package ai.xng;

import static ai.xng.TestUtil.threadPool;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import lombok.val;

@Timeout(value = 1)
public class ContextTest {
  @Test
  public void testEmptyReinforcement() {
    Context.newImmediate().reinforce(0).join();
  }

  @Test
  public void testSingleNeverReinforcement() {
    val context = Context.newImmediate();
    context.getScheduler().scheduleDirect(() -> context.nodeState(new Node()));
    context.reinforce(1).join();
  }

  @Test
  public void testSingleOnceReinforcement() {
    val context = Context.newImmediate();
    new Node().activate(context);
    context.reinforce(1).join();
  }

  /**
   * Normally, Hebbian learning contributes evidence towards meeting the
   * activation threshold for the posterior. However, in the case where the
   * posterior is already hyperactivated, naive arithmetic would then actually
   * yield an inhibitory weight, which does not make sense.
   */
  @Test
  public void testHebbianLearningOnHyperactivation() throws InterruptedException {
    val context = Context.newImmediate();
    val a = new Node(), b = new Node(), c = new SynapticNode();
    c.synapse.setCoefficient(a, 2);
    c.synapse.setDecayPeriod(a, 1000);
    a.activate(context);
    b.activate(context);
    c.activate(context);
    context.reinforce(1).join();
    assertThat(c.synapse.getCoefficient(b)).isGreaterThanOrEqualTo(0);
  }

  /**
   * Consistent negative reinforcement should break a connection.
   */
  @Test
  public void testBreakHabit() {
    val a = new Node(), b = new SynapticNode();
    a.then(b);
    val monitor = new EmissionMonitor<>(b.rxActivate());

    val fixture = new LearningFixture(100, 1000);
    do {
      val context = Context.newWithExecutor(threadPool);
      a.activate(context);
      context.blockUntilIdle();
      fixture.reinforce(!monitor.didEmit(), context, 0, -1);
    } while (fixture.shouldContinue());
    assertThat(b.synapse.getCoefficient(a)).isLessThan(1);
  }

  /**
   * Ensures that negative reinforcement in the presence of a novel prior
   * generates an inhibitory weight.
   */
  @Test
  public void testDifferentialReinforcement() {
    val a = new Node(), b = new Node(), c = new SynapticNode();
    a.then(c);
    val monitor = new EmissionMonitor<>(c.rxActivate());

    val fixture = new LearningFixture(200, 2000);
    do {
      {
        val context = Context.newWithExecutor(threadPool);
        a.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(monitor.didEmit(), context, 1, -1);
      }
      {
        val context = Context.newWithExecutor(threadPool);
        b.activate(context);
        a.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(!monitor.didEmit(), context, 1, -1);
      }
    } while (fixture.shouldContinue());
  }

  /**
   * Ensures that positive reinforcement does not destabilize the system.
   */
  @Test
  public void testPositiveStability() {
    val a = new Node(), b = new Node(), c = new Node(), d = new SynapticNode();
    a.comment = "a";
    b.comment = "b";
    c.comment = "c";
    d.comment = "d";
    d.conjunction(a, b);
    val monitor = new EmissionMonitor<>(d.rxActivate());

    final float weight = 1;
    val fixture = new LearningFixture(700, 7000);
    do {
      {
        val context = Context.newWithExecutor(threadPool);
        a.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(!monitor.didEmit(), context, weight, -weight, "a");
      }
      {
        val context = Context.newWithExecutor(threadPool);
        b.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(!monitor.didEmit(), context, weight, -weight, "b");
      }
      {
        val context = Context.newWithExecutor(threadPool);
        c.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(!monitor.didEmit(), context, weight, -weight, "c");
      }
      {
        val context = Context.newWithExecutor(threadPool);
        a.activate(context);
        b.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(monitor.didEmit(), context, weight, -weight, "ab");
      }
      {
        val context = Context.newWithExecutor(threadPool);
        a.activate(context);
        c.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(!monitor.didEmit(), context, weight, -weight, "ac");
      }
      {
        val context = Context.newWithExecutor(threadPool);
        b.activate(context);
        c.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(!monitor.didEmit(), context, weight, -weight, "bc");
      }
      {
        val context = Context.newWithExecutor(threadPool);
        a.activate(context);
        b.activate(context);
        c.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(monitor.didEmit(), context, weight, -weight, "abc");
      }
    } while (fixture.shouldContinue());
  }

  /**
   * Ensures that indescriminate negative reinforcement does not cause runaway
   * divergence.
   */
  @Test
  public void testNegativeStability() {
    val a = new Node(), b = new Node(), c = new SynapticNode();
    c.conjunction(a, b);

    for (int i = 0; i < 1000; ++i) {
      {
        val context = Context.newWithExecutor(threadPool);
        a.activate(context);
        b.activate(context);
        context.blockUntilIdle();
        context.reinforce(-1).join();
      }
      {

        val context = Context.newWithExecutor(threadPool);
        b.activate(context);
        a.activate(context);
        context.blockUntilIdle();
        context.reinforce(-1).join();
      }
    }

    assertThat(c.synapse.getCoefficient(a)).isBetween(-10f, 10f);
    assertThat(c.synapse.getCoefficient(b)).isBetween(-10f, 10f);
  }

  /**
   * Ensures that indescriminate negative reinforcement does not destabilize the
   * system unrecoverably.
   */
  @Test
  public void testNegativeRecovery() {
    val a1 = new Node(), a2 = new SynapticNode(), b1 = new Node(), b2 = new SynapticNode(), c1 = new SynapticNode(),
        c2 = new SynapticNode();
    a1.then(a2);
    b1.then(b2);
    c1.then(c2);
    c1.conjunction(a2, b2);

    // Phase 1: indescriminate punishment
    for (int i = 0; i < 100; ++i) {
      {
        val context = Context.newWithExecutor(threadPool);
        a1.activate(context);
        context.blockUntilIdle();
        context.reinforce(-1).join();
      }
      {
        val context = Context.newWithExecutor(threadPool);
        b1.activate(context);
        context.blockUntilIdle();
        context.reinforce(-1).join();
      }
      {
        val context = Context.newWithExecutor(threadPool);
        a1.activate(context);
        b1.activate(context);
        context.blockUntilIdle();
        context.reinforce(-1).join();
      }
    }

    // Phase 2: recovery
    val monitor = new EmissionMonitor<>(c2.rxActivate());
    val fixture = new LearningFixture(300, 3000);
    do {
      {
        val context = Context.newWithExecutor(threadPool);
        a1.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(!monitor.didEmit(), context, 1, -1);
      }
      {
        val context = Context.newWithExecutor(threadPool);
        b1.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(!monitor.didEmit(), context, 1, -1);
      }
      {
        val context = Context.newWithExecutor(threadPool);
        a1.activate(context);
        b1.activate(context);
        context.blockUntilIdle();
        fixture.reinforce(monitor.didEmit(), context, 1, -1);
      }
    } while (fixture.shouldContinue());
  }

  @Test
  public void testRecency() {
    val context = Context.newImmediate();
    val a = new Node(), b = new Node();
    a.activate(context);
    b.activate(context);
    val other = Context.newImmediate();
    a.activate(other);
    assertThat(context.snapshotNodes()).containsExactly(b, a);
  }
}
