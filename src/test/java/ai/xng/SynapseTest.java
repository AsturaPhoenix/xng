package ai.xng;

import static ai.xng.TestUtil.threadPool;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import lombok.val;

public class SynapseTest {
  @Test
  public void testActivation() {
    val s = new Synapse();
    val incoming = new Node();
    val monitor = new EmissionMonitor<>(s.rxActivate());

    s.setCoefficient(incoming, 1);
    incoming.activate(Context.newWithExecutor(threadPool));
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testNoDups() {
    val s = new Synapse();
    val a = new Node(), b = new Node();
    val monitor = new EmissionMonitor<>(s.rxActivate());
    s.setCoefficient(a, 2);
    s.setDecayPeriod(a, 1000);
    s.setCoefficient(b, 2);
    s.setDecayPeriod(b, 1000);
    val context = Context.newWithExecutor(threadPool);
    a.activate(context);
    assertTrue(monitor.didEmit());
    b.activate(context);
    assertFalse(monitor.didEmit());
  }
}
