package ai.xng;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import lombok.val;

public class SynapseTest {
  @Test
  public void testActivation() {
    val s = new Synapse();
    val incoming = new Node();
    val monitor = new EmissionMonitor<>(s.rxActivate());

    s.setCoefficient(incoming, 1);
    incoming.activate(new Context());
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testNoZeroDups() {
    val s = new Synapse();
    val a = new Node(), b = new Node();
    val monitor = new EmissionMonitor<>(s.rxActivate());
    s.setCoefficient(a, 2);
    s.setDecayPeriod(a, 1000);
    s.setCoefficient(b, 0);
    val context = new Context();
    a.activate(context);
    assertTrue(monitor.didEmit());
    b.activate(context);
    assertFalse(monitor.didEmit());
  }
}
