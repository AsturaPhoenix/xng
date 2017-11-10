package io.tqi.ekg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.tqi.ekg.KnowledgeBase.BuiltIn;
import io.tqi.ekg.KnowledgeBase.Common;

public class KnowledgeBaseTest {
    @Test
    public void testEmptySerialization() throws Exception {
        assertNotNull(TestUtil.serialize(new KnowledgeBase()));
    }

    private static void testPrint(final KnowledgeBase kb) {
        final EmissionMonitor<String> monitor = new EmissionMonitor<>(kb.rxOutput());
        kb.invoke(kb.node(BuiltIn.print), kb.valueNode("foo"), null);
        assertEquals("foo", monitor.emissions().blockingFirst());
    }

    @Test
    public void testPrint() {
        testPrint(new KnowledgeBase());
    }

    @Test
    public void testPrintAfterSerialization() throws Exception {
        testPrint(TestUtil.serialize(new KnowledgeBase()));
    }

    private static void setUpPropGet(final KnowledgeBase kb) {
        kb.node("roses").setProperty(kb.node("color"), kb.valueNode("red"));

        // @formatter:off
        kb.node("roses are")
                .setProperty(kb.node(Common.execute), kb.node(BuiltIn.getProperty))
                .setProperty(kb.node(Common.argument), kb.node()
                        .setProperty(kb.node(Common.object), kb.node("roses"))
                        .setProperty(kb.node(Common.property), kb.node("color")))
                .setProperty(kb.node(Common.callback), kb.node(BuiltIn.print));
        // @formatter:on
    }

    private static void assertPropGet(final KnowledgeBase kb) {
        final EmissionMonitor<String> monitor = new EmissionMonitor<>(kb.rxOutput());
        kb.node("roses are").activate();
        assertEquals("red", monitor.emissions().blockingFirst());
    }

    @Test
    public void testPrintProp() {
        try (final KnowledgeBase kb = new KnowledgeBase()) {
            setUpPropGet(kb);
            assertPropGet(kb);
        }
    }

    @Test
    public void testPrintPropAfterSerialization() throws Exception {
        final KnowledgeBase kb = new KnowledgeBase();
        setUpPropGet(kb);
        assertPropGet(TestUtil.serialize(kb));
    }

    @Test
    public void testSetException() {
        try (final KnowledgeBase kb = new KnowledgeBase()) {
            final EmissionMonitor<?> monitor = new EmissionMonitor<>(kb.node(Common.exception).rxChange());
            kb.node(Common.exception).setProperty(kb.node(Common.source), kb.valueNode(new Exception()));
            assertTrue(monitor.didEmit());
        }
    }
}
