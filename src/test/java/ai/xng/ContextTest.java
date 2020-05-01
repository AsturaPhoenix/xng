package ai.xng;

import static ai.xng.TestUtil.threadPool;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import lombok.val;

public class ContextTest {
  @Test
  public void testEmptyReinforcement() {
    Context.newImmediate().reinforce(0);
  }

  @Test
  public void testSingleNeverReinforcement() {
    val context = Context.newImmediate();
    context.getScheduler().scheduleDirect(() -> context.nodeState(new Node()));
    context.reinforce(1);
  }

  @Test
  public void testSingleOnceReinforcement() {
    val context = Context.newImmediate();
    new Node().activate(context);
    context.reinforce(1);
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
    context.hebbianReinforcement(c, 1);
    assertThat(c.synapse.getCoefficient(b)).isGreaterThanOrEqualTo(0);
  }

  /**
   * Consistent negative reinforcement should break a connection.
   */
  @Test
  public void testBreakHabit() {
    val a = new Node(), b = new SynapticNode();
    a.then(b);
    val monitor = new SynchronousEmissionMonitor<>(b.rxActivate());

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
    val monitor = new SynchronousEmissionMonitor<>(c.rxActivate());

    val fixture = new LearningFixture(100, 1000);
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
  public void testHebbianPositiveStability() {
    val a = new Node(), b = new Node(), c = new Node(), d = new SynapticNode();
    a.comment = "a";
    b.comment = "b";
    c.comment = "c";
    d.comment = "d";
    d.conjunction(a, b);
    val monitor = new SynchronousEmissionMonitor<>(d.rxActivate());

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
