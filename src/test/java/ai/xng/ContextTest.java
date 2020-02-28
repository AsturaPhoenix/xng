package ai.xng;

import static org.junit.Assert.assertTrue;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import lombok.val;

public class ContextTest {
  @Test
  public void testEmptyReinforcement() {
    new Context(Node::new).reinforce(Optional.empty(), Optional.empty(), 0);
  }

  @Test
  public void testSingleNeverReinforcement() {
    val context = new Context(Node::new);
    context.nodeState(new Node());
    context.reinforce(Optional.empty(), Optional.empty(), 0);
  }

  @Test
  public void testSingleOnceReinforcement() {
    val context = new Context(Node::new);
    new Node().activate(context);
    context.reinforce(Optional.empty(), Optional.empty(), 0);
  }

  /**
   * This test covers a historic deadlock between activation (which would lock
   * part of the context and then the activation queue) and reinforcement (which
   * locks the activation queue and then possibly part of the context).
   */
  @Test
  public void testParallelActivationAndReinforcement() {
    for (int i = 0; i < 40000; ++i) {
      val context = new Context(Node::new);
      val a = new Node();
      val b = new Node();

      try (val ref = context.new Ref()) {
        new Thread(() -> {
          a.activate(context);
          context.reinforce(Optional.empty(), Optional.empty(), 0);
        }).start();
        new Thread(() -> b.activate(context)).start();
      }

      context.rxActive().filter(active -> !active).timeout(5, TimeUnit.SECONDS).blockingFirst();
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
    val context = new Context(Node::new);
    val a = new Node(), b = new Node(), c = new Node();
    c.getSynapse().setCoefficient(a, 2);
    c.getSynapse().setDecayPeriod(a, 1000);
    a.activate(context);
    Thread.sleep(250);
    b.activate(context);
    context.blockUntilIdle();
    c.activate(context);
    context.blockUntilIdle();
    assertTrue(c.getSynapse().getCoefficient(b) + " >= 0", c.getSynapse().getCoefficient(b) >= 0);
  }
}
