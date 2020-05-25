package ai.xng;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import lombok.val;

public class SynapseTest {
  @Test
  public void testActivation() {
    val outgoing = new SynapticNode();
    val incoming = new Node();
    val monitor = new EmissionMonitor<>(outgoing.rxActivate());

    incoming.then(outgoing);
    incoming.activate(Context.newImmediate());
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testNoDups() {
    val a = new Node(), b = new Node(), c = new SynapticNode();
    val monitor = new EmissionMonitor<>(c.rxActivate());
    c.getSynapse().setCoefficient(a, 2);
    c.getSynapse().setDecayPeriod(a, 1000);
    c.getSynapse().setCoefficient(b, 2);
    c.getSynapse().setDecayPeriod(b, 1000);
    val context = Context.newImmediate();
    a.activate(context);
    assertTrue(monitor.didEmit());
    b.activate(context);
    assertFalse(monitor.didEmit());
  }
}
