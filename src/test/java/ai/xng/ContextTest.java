package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import lombok.val;

public class ContextTest {
  @Test
  public void testEmptyReinforcement() {
    Context.newImmediate().reinforce(Optional.empty(), Optional.empty(), 0);
  }

  @Test
  public void testSingleNeverReinforcement() {
    val context = Context.newImmediate();
    context.getScheduler().scheduleDirect(() -> context.nodeState(new Node()));
    context.reinforce(Optional.empty(), Optional.empty(), 0);
  }

  @Test
  public void testSingleOnceReinforcement() {
    val context = Context.newImmediate();
    new Node().activate(context);
    context.reinforce(Optional.empty(), Optional.empty(), 0);
  }

  /**
   * This test covers a historic deadlock between activation (which would lock
   * part of the context and then the activation queue) and reinforcement (which
   * locked the activation queue and then possibly part of the context).
   */
  @Test
  public void testParallelActivationAndReinforcement() {
    val threads = Executors.newFixedThreadPool(2);
    for (int i = 0; i < 40000; ++i) {
      val context = Context.newDedicated();
      val a = new Node();
      val b = new Node();

      try (val ref = context.new Ref()) {
        threads.submit(() -> {
          a.activate(context);
          context.reinforce(Optional.empty(), Optional.empty(), 0);
        });
        threads.submit(() -> b.activate(context));
      }

      context.blockUntilIdle(Duration.ofSeconds(5));
    }
  }

  /**
   * Normally, Hebbian learning contributes evidence towards meeting the
   * activation threshold for the posterior. However, in the case where the
   * posterior is already hyperactivated, naive arithmetic would then actually
   * yield an inhibitory weight, which does not make sense.
   */
  @Test
  public void testHebbianLearningOnHyperactivation() throws InterruptedException {
    val context = Context.newDedicated();
    val a = new Node(), b = new Node(), c = new SynapticNode();
    c.synapse.setCoefficient(a, 2);
    c.synapse.setDecayPeriod(a, 1000);
    a.activate(context);
    Thread.sleep(250);
    b.activate(context);
    context.blockUntilIdle();
    c.activate(context);
    context.blockUntilIdle();
    assertThat(c.synapse.getCoefficient(b)).isGreaterThanOrEqualTo(0);
  }

  @Test
  public void testRecency() {
    val context = Context.newImmediate();
    val a = new Node(), b = new Node();
    a.activate(context);
    b.activate(context);
    val other = Context.newImmediate();
    a.activate(other);
    assertThat(context.snapshotNodes()).containsExactly(a, b);
  }
}
