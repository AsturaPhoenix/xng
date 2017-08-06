package io.tqi.ekg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

import io.tqi.ekg.KnowledgeBase.BuiltIn;

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

        final Node arg = kb.node();
        arg.setProperty(kb.node("object"), kb.node("roses"));
        arg.setProperty(kb.node("property"), kb.node("color"));

        final Node invocation = kb.node("roses are");
        invocation.setProperty(kb.EXECUTE, kb.node(BuiltIn.getProperty));
        invocation.setProperty(kb.ARGUMENT, arg);
    }

    private static void assertPropGet(final KnowledgeBase kb) {
        final EmissionMonitor<String> monitor = new EmissionMonitor<>(kb.rxOutput());
        kb.invoke(kb.node(BuiltIn.print), kb.node("roses are"), null);
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
            // kb.EXCEPTION.rxChange().subscribe(x -> {
            // }, x -> fail());
            final EmissionMonitor<?> monitor = new EmissionMonitor<>(kb.EXCEPTION.rxChange());
            kb.EXCEPTION.setProperty(kb.node("source"), kb.valueNode(new Exception()));
            assertTrue(monitor.didEmit());
        }
    }

    private static void setUpIterator(final KnowledgeBase kb) {
        kb.EXCEPTION.rxActivate().subscribe(x -> {
            final Node exceptionValueNode = kb.EXCEPTION.getProperty(kb.ARGUMENT);
            if (exceptionValueNode != null) {
                fail(Throwables.getStackTraceAsString((Throwable) exceptionValueNode.getValue()));
            } else {
                fail("Exception node activated");
            }
        });

        final Node content = kb.valueNode(ImmutableList.of("foo"));

        final Node iterator = kb.node();

        // @formatter:off
        // create an executable node that derives a Java iterator
        final Node invoke = kb.node()
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.invoke))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.OBJECT, kb.node()
                                .setProperty(kb.EXECUTE, kb.node(BuiltIn.getProperty))
                                .setProperty(kb.ARGUMENT, kb.node()
                                        .setProperty(kb.OBJECT, iterator)
                                        .setProperty(kb.PROPERTY, kb.ARGUMENT)))
                        .setProperty(kb.METHOD, kb.valueNode("iterator")));
        invoke.getSynapse().setCoefficient(iterator, 1);

        // add appropriate methods for the iterator node
		final Node createMethods = kb.node();
		invoke.setProperty(kb.CALLBACK, createMethods);
		//create onMove
		kb.node().setProperty(kb.EXECUTE, kb.node(BuiltIn.setProperty))
		         .setProperty(kb.ARGUMENT, kb.node()
		                 .setProperty(kb.OBJECT, kb.node()
		                         .setProperty(kb.EXECUTE, kb.node(BuiltIn.getProperty))
		                         .setProperty(kb.ARGUMENT, kb.node()
		                                 .setProperty(kb.OBJECT, createMethods)
		                                 .setProperty(kb.PROPERTY, kb.ARGUMENT)))
			             .setProperty(kb.PROPERTY, kb.node("onMove"))
			             .setProperty(kb.VALUE, kb.node()
			                     .setProperty(kb.EXECUTE, kb.node(BuiltIn.node))))
				.getSynapse().setCoefficient(createMethods, 1);
		// create forward
		final Node createForward = kb.node()
		        .setProperty(kb.EXECUTE, kb.node(BuiltIn.setProperty))
		        .setProperty(kb.ARGUMENT, kb.node()
		                .setProperty(kb.OBJECT, kb.node()
		                        .setProperty(kb.EXECUTE, kb.node(BuiltIn.getProperty))
	                            .setProperty(kb.ARGUMENT, kb.node()
	                                    .setProperty(kb.OBJECT, createMethods)
                                        .setProperty(kb.PROPERTY, kb.ARGUMENT)))
			            .setProperty(kb.PROPERTY, kb.node("forward"))
				        .setProperty(kb.VALUE, kb.node()
				                .setProperty(kb.EXECUTE, kb.node(BuiltIn.node))));
		createForward.getSynapse().setCoefficient(createMethods, 1);
		// implement forward
		final Node nextCallback = kb.node();
		kb.node().setProperty(kb.EXECUTE, kb.node(BuiltIn.invoke))
		         .setProperty(kb.ARGUMENT, kb.node()
		                 // This is not sufficient because args are only evaluated at the top level; properties of
		                 // args are not evaluated. We need to go ahead and stop relying on kb invocation magic.
		                 .setProperty(kb.OBJECT, kb.node()
		                         .setProperty(kb.EXECUTE, kb.node(BuiltIn.getProperty))
		                         .setProperty(kb.ARGUMENT, kb.node()
		                                 .setProperty(kb.OBJECT, createMethods)
		                                 .setProperty(kb.PROPERTY, kb.ARGUMENT)))
		                 .setProperty(kb.METHOD, kb.valueNode("next")))
		         .setProperty(kb.CALLBACK, nextCallback);
		kb.node().setProperty(kb.EXECUTE, kb.node(BuiltIn.setProperty))
                 .setProperty(kb.ARGUMENT, kb.node()
                         .setProperty(kb.OBJECT, kb.node()
                                 .setProperty(kb.EXECUTE, kb.node(BuiltIn.getProperty))
                                 .setProperty(kb.ARGUMENT, kb.node()
                                         .setProperty(kb.OBJECT, kb.node()
                                                 .setProperty(kb.EXECUTE, kb.node(BuiltIn.getProperty))
                                                 .setProperty(kb.ARGUMENT, kb.node()
                                                         .setProperty(kb.OBJECT, createMethods)
                                                         .setProperty(kb.PROPERTY, kb.ARGUMENT)))
                                         .setProperty(kb.PROPERTY, kb.node("onMove"))))
                         .setProperty(kb.PROPERTY, kb.ARGUMENT)
                         .setProperty(kb.VALUE, kb.node()
                                 .setProperty(kb.EXECUTE, kb.node(BuiltIn.getProperty))
                                 .setProperty(kb.ARGUMENT, kb.node()
                                         .setProperty(kb.OBJECT, nextCallback)
                                         .setProperty(kb.PROPERTY, kb.ARGUMENT))));
        // @formatter:on

        final Node callback = kb.node();
        final EmissionMonitor<?> monitor = new EmissionMonitor<>(callback.rxActivate());
        kb.invoke(iterator, content, callback);
        assertTrue(monitor.didEmit());
        final Node testIterator = callback.getProperty(kb.ARGUMENT);
        kb.indexNode("test iterator", testIterator);
        final Node onMove = testIterator.getProperty(kb.node("onMove"));
        onMove.setProperty(kb.EXECUTE, kb.node(BuiltIn.print));
    }

    private static void assertIterator(final KnowledgeBase kb) {
        final Node testIterator = kb.node("test iterator");
        final Node onMove = testIterator.getProperty(kb.node("onMove"));

        final EmissionMonitor<String> outputMonitor = new EmissionMonitor<>(kb.rxOutput());
        final EmissionMonitor<?> onMoveMonitor = new EmissionMonitor<>(onMove.rxActivate());
        testIterator.getProperty(kb.node("forward")).activate();
        assertTrue(onMoveMonitor.didEmit());
        assertEquals("foo", onMove.getProperty(kb.ARGUMENT).getValue());
        assertEquals("foo", outputMonitor.emissions().blockingSingle());
    }

    @Test
    public void testIterator() {
        try (final KnowledgeBase kb = new KnowledgeBase()) {
            setUpIterator(kb);
            assertIterator(kb);
        }
    }

    @Test
    public void testIteratorAfterSerialization() throws Exception {
        final KnowledgeBase kb = new KnowledgeBase();
        setUpIterator(kb);
        assertIterator(TestUtil.serialize(kb));
    }

    /*
     * private static Node function(final KnowledgeBase kb, final
     * KnowledgeBase.BuiltIn execute, final Node argument) { return
     * kb.node().setProperty(kb.EXECUTE,
     * kb.node(execute)).setProperty(kb.ARGUMENT, argument); }
     * 
     * private static Node getProperty(final KnowledgeBase kb, final Node
     * object, final Node property) { return function(kb, BuiltIn.getProperty,
     * kb.node().setProperty(kb.node("object"),
     * object).setProperty(kb.node("property"), property)); }
     * 
     * private static Node setProperty(final KnowledgeBase kb, final Node
     * object, final Node property, final Node value) { return function(kb,
     * BuiltIn.getProperty, kb.node().setProperty(kb.node("object"), object)
     * .setProperty(kb.node("property"), property).setProperty(kb.node("value"),
     * value)); }
     * 
     * @Test public void testFibbonacci() { try (final KnowledgeBase kb = new
     * KnowledgeBase()) { final Node fib = kb.node(), a = kb.node(), b =
     * kb.node(), c = kb.node(); fib.setProperty(a,
     * kb.valueNode(0)).setProperty(b, kb.valueNode(1));
     * 
     * final Node print = function(kb, BuiltIn.print, getProperty(kb, fib, b));
     * 
     * final Node add = setProperty(kb, fib, c, function(kb, BuiltIn.mathAdd,
     * kb.node() .setProperty(kb.arg(0), getProperty(kb, fib,
     * a)).setProperty(kb.arg(1), getProperty(kb, fib, b))));
     * 
     * final Node bToA = setProperty(kb, fib, a, getProperty(kb, fib, b)), cToB
     * = setProperty(kb, fib, b, getProperty(kb, fib, c));
     * 
     * print.getSynapse().setCoefficient(fib, 1);
     * add.getSynapse().setCoefficient(print, 1);
     * bToA.getSynapse().setCoefficient(add, 1);
     * cToB.getSynapse().setCoefficient(bToA, 1);
     * fib.getSynapse().setCoefficient(cToB, 2).setDecayPeriod(cToB,
     * 200).setCoefficient(node, coefficient); } }
     */
}
