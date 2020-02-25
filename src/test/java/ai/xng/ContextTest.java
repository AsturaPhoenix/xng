package ai.xng;

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

      context.addRef();
      try {
        new Thread(() -> {
          a.activate(context);
          context.reinforce(Optional.empty(), Optional.empty(), 0);
        }).start();
        new Thread(() -> b.activate(context)).start();
      } finally {
        context.releaseRef();
      }

      context.rxActive().filter(active -> !active).timeout(5, TimeUnit.SECONDS).blockingFirst();
    }
  }
}
