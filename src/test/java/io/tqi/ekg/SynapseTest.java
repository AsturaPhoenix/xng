package io.tqi.ekg;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SynapseTest {
    @Test
    public void testNoZeroDups() throws Exception {
        final Synapse s = new Synapse();
        final Node a = new Node(), b = new Node();
        final EmissionMonitor<?> monitor = new EmissionMonitor<>(s.rxActivate());
        s.setCoefficient(a, 2);
        s.setDecayPeriod(a, 1000);
        s.setCoefficient(b, 0);
        a.activate();
        assertTrue(monitor.didEmit());
        b.activate();
        assertFalse(monitor.didEmit());

    }
}
