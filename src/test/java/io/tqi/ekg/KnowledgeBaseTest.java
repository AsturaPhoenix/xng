package io.tqi.ekg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Iterator;
import java.util.List;

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

        // @formatter:off
        kb.node("roses are")
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.getProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.OBJECT, kb.node("roses"))
                        .setProperty(kb.PROPERTY, kb.node("color")))
                .setProperty(kb.CALLBACK, kb.node(BuiltIn.print));
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
            final EmissionMonitor<?> monitor = new EmissionMonitor<>(kb.EXCEPTION.rxChange());
            kb.EXCEPTION.setProperty(kb.SOURCE, kb.valueNode(new Exception()));
            assertTrue(monitor.didEmit());
        }
    }

    private static void setUpIterator(final KnowledgeBase kb) {
        kb.EXCEPTION.rxActivate().subscribe(x -> {
            final Node exceptionValueNode = kb.EXCEPTION.getProperty(kb.VALUE);
            if (exceptionValueNode != null) {
                fail(Throwables.getStackTraceAsString((Throwable) exceptionValueNode.getValue()));
            } else {
                fail("Exception node activated: " + Throwables
                        .getStackTraceAsString((Throwable) kb.EXCEPTION.getProperty(kb.ARGUMENT).getValue()));
            }
        });

        final Node content = kb.valueNode(ImmutableList.of("foo"));

        // @formatter:off        
        final Node createNodeAt = kb.node(),
                setDestCb = kb.node(),
                createNodeCb = kb.node(),
                createNodeCbArg = kb.node(),
                cpCb = kb.node(),
                cpProp = kb.node();
        createNodeAt.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.node))
                .setProperty(kb.CALLBACK, createNodeCb);
        createNodeAt.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.SOURCE, kb.node()
                                .setProperty(kb.OBJECT, createNodeAt)
                                .setProperty(kb.ARGUMENT, kb.ARGUMENT))
                        .setProperty(kb.DESTINATION, kb.node()
                                .setProperty(kb.OBJECT, createNodeCbArg)
                                .setProperty(kb.PROPERTY, kb.DESTINATION)))
                .setProperty(kb.CALLBACK, setDestCb);
        createNodeAt.then(cpCb)
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.SOURCE, kb.node()
                                .setProperty(kb.OBJECT, createNodeAt)
                                .setProperty(kb.PROPERTY, kb.CALLBACK))
                        .setProperty(kb.DESTINATION, kb.node()
                                .setProperty(kb.OBJECT, cpProp)
                                .setProperty(kb.PROPERTY, kb.CALLBACK)));
        cpProp
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, createNodeCbArg
                        .setProperty(kb.SOURCE, kb.node()
                                .setProperty(kb.OBJECT, createNodeCb)
                                .setProperty(kb.PROPERTY, kb.ARGUMENT)))
                .getSynapse()
                        .setCoefficient(createNodeCb, .34f)
                        .setCoefficient(setDestCb, .34f)
                        .setCoefficient(cpCb, .34f);

        final Node iterator = kb.node();
        
        final Node cpContentCb = kb.node(), jIterInvokeArg = kb.node(), createMethods = kb.node();
        iterator.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.SOURCE, kb.node()
                                .setProperty(kb.OBJECT, iterator)
                                .setProperty(kb.PROPERTY, kb.ARGUMENT))
                        .setProperty(kb.DESTINATION, kb.node()
                                .setProperty(kb.OBJECT, jIterInvokeArg)
                                .setProperty(kb.PROPERTY, kb.OBJECT)))
                .setProperty(kb.CALLBACK, cpContentCb);
        cpContentCb.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.invoke))
                .setProperty(kb.ARGUMENT, jIterInvokeArg
                        .setProperty(kb.CLASS, kb.valueNode(List.class))
                        .setProperty(kb.METHOD, kb.valueNode("iterator")))
                .setProperty(kb.CALLBACK, createMethods);

        // onMove
        final Node createOnMoveArg = kb.node(),
                cpCreateOnMoveArgCb = kb.node(),
                createForward = kb.node();
        createMethods.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.SOURCE, kb.node()
                                .setProperty(kb.OBJECT, createMethods)
                                .setProperty(kb.PROPERTY, kb.ARGUMENT))
                        .setProperty(kb.DESTINATION, kb.node()
                                .setProperty(kb.OBJECT, createOnMoveArg)
                                .setProperty(kb.PROPERTY, kb.OBJECT)))
                .setProperty(kb.CALLBACK, cpCreateOnMoveArgCb);
        cpCreateOnMoveArgCb.then(kb.node())
                        .setProperty(kb.EXECUTE, createNodeAt)
                        .setProperty(kb.ARGUMENT, createOnMoveArg
                                .setProperty(kb.PROPERTY, kb.node("onMove")))
                        .setProperty(kb.CALLBACK, createForward);
        
        final Node createForwardArg = kb.node(),
                cpCreateForwardArgCb = kb.node(),
                implementForward = kb.node();
        createForward.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.SOURCE, kb.node()
                                .setProperty(kb.OBJECT, createMethods)
                                .setProperty(kb.PROPERTY, kb.ARGUMENT))
                        .setProperty(kb.DESTINATION, kb.node()
                                .setProperty(kb.OBJECT, createForwardArg)
                                .setProperty(kb.PROPERTY, kb.OBJECT)))
                .setProperty(kb.CALLBACK, cpCreateForwardArgCb);
        cpCreateForwardArgCb.then(kb.node())
                        .setProperty(kb.EXECUTE, createNodeAt)
                        .setProperty(kb.ARGUMENT, createOnMoveArg
                                .setProperty(kb.PROPERTY, kb.node("forward")))
                        .setProperty(kb.CALLBACK, implementForward);
        
        final Node forwardImpl = kb.node(),
                cpNextInvokeArgCb = kb.node(),
                nextInvokeArg = kb.node(),
                nextCb = kb.node(),
                iterClass = kb.valueNode(Iterator.class);
        forwardImpl.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.SOURCE, kb.node()
                                .setProperty(kb.OBJECT, forwardImpl)
                                .setProperty(kb.PROPERTY, kb.ARGUMENT))
                        .setProperty(kb.DESTINATION, kb.node()
                                .setProperty(kb.OBJECT, nextInvokeArg)
                                .setProperty(kb.PROPERTY, kb.OBJECT)))
                .setProperty(kb.CALLBACK, cpNextInvokeArgCb);
        cpNextInvokeArgCb.then(kb.node())
                        .setProperty(kb.EXECUTE, kb.node(BuiltIn.invoke))
                        .setProperty(kb.ARGUMENT, nextInvokeArg
                                .setProperty(kb.CLASS, iterClass)
                                .setProperty(kb.METHOD, kb.valueNode("next")))
                        .setProperty(kb.CALLBACK, nextCb);
        
        final Node cpOnMoveSource = kb.node(),
                cpOnMoveCb = kb.node(),
                currentInvokeArg = kb.node(),
                cpCurrentInvokeArgCb = kb.node(),
                cpCpOnMoveSourceCb = kb.node(),
                execOnMove = kb.node();
        
        nextCb.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.SOURCE, kb.node()
                                .setProperty(kb.OBJECT, forwardImpl)
                                .setProperty(kb.PROPERTY, kb.ARGUMENT))
                        .setProperty(kb.DESTINATION, kb.node()
                                .setProperty(kb.OBJECT, currentInvokeArg)
                                .setProperty(kb.PROPERTY, kb.OBJECT)))
                .setProperty(kb.CALLBACK, cpCurrentInvokeArgCb);
        cpCurrentInvokeArgCb.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.SOURCE, kb.node()
                                .setProperty(kb.OBJECT, forwardImpl)
                                .setProperty(kb.PROPERTY, kb.ARGUMENT))
                        .setProperty(kb.DESTINATION, kb.node()
                                .setProperty(kb.OBJECT, cpOnMoveSource)
                                .setProperty(kb.PROPERTY, kb.OBJECT)))
                .setProperty(kb.CALLBACK, cpCpOnMoveSourceCb);
        cpCpOnMoveSourceCb.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.SOURCE, cpOnMoveSource
                                .setProperty(kb.PROPERTY, kb.node("onMove")))
                        .setProperty(kb.DESTINATION, kb.node()
                                .setProperty(kb.OBJECT, execOnMove)
                                .setProperty(kb.PROPERTY, kb.EXECUTE)))
                .setProperty(kb.CALLBACK, cpOnMoveCb);
        
        // we could invoke onMove right on the callback to "current", but we also want to forward
        // the forwardImpl callback to onMove
        
        final Node cpCallbackCb = kb.node();
        cpOnMoveCb.then(kb.node())
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
                .setProperty(kb.ARGUMENT, kb.node()
                        .setProperty(kb.SOURCE, kb.node()
                                .setProperty(kb.OBJECT, forwardImpl)
                                .setProperty(kb.PROPERTY, kb.CALLBACK))
                        .setProperty(kb.DESTINATION, kb.node()
                                .setProperty(kb.OBJECT, execOnMove)
                                .setProperty(kb.PROPERTY, kb.CALLBACK)))
                .setProperty(kb.CALLBACK, cpCallbackCb);
        
        cpCallbackCb
                .setProperty(kb.EXECUTE, kb.node(BuiltIn.invoke))
                .setProperty(kb.ARGUMENT, currentInvokeArg
                        .setProperty(kb.CLASS, iterClass)
                        .setProperty(kb.METHOD, kb.valueNode("current")))
                .then(execOnMove);
        
		// implement forward by hooking up to forwardImpl
        final Node setForwardExecuteArg = kb.node()
                .setProperty(kb.PROPERTY, kb.EXECUTE)
                .setProperty(kb.VALUE, forwardImpl);
        final Node implementForwardCb = kb.node();
		implementForward.then(kb.node())
		        .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
		        .setProperty(kb.ARGUMENT, kb.node()
		                .setProperty(kb.SOURCE, kb.node()
		                        .setProperty(kb.OBJECT, implementForward)
		                        .setProperty(kb.PROPERTY, kb.ARGUMENT))
		                .setProperty(kb.DESTINATION, kb.node()
		                        .setProperty(kb.OBJECT, setForwardExecuteArg)
		                        .setProperty(kb.PROPERTY, kb.OBJECT)))
		        .then(implementForwardCb);
		
		final Node setForwardExecuteCb = kb.node();
		implementForwardCb.then(kb.node())
		        .setProperty(kb.EXECUTE, kb.node(BuiltIn.setProperty))
		        .setProperty(kb.ARGUMENT, setForwardExecuteArg)
		        .setProperty(kb.CALLBACK, setForwardExecuteCb);
		final Node finalCallback = kb.node(), cpFinalCallbackCb = kb.node();
		setForwardExecuteCb.then(kb.node())
		        .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
		        .setProperty(kb.SOURCE, kb.node()
		                .setProperty(kb.OBJECT, iterator)
		                .setProperty(kb.PROPERTY, kb.CALLBACK))
		        .setProperty(kb.DESTINATION, kb.node()
		                .setProperty(kb.OBJECT, finalCallback)
		                .setProperty(kb.PROPERTY, kb.EXECUTE))
		        .setProperty(kb.CALLBACK, cpFinalCallbackCb);
		cpFinalCallbackCb.then(kb.node())
		        .setProperty(kb.EXECUTE, kb.node(BuiltIn.copyProperty))
		        .setProperty(kb.ARGUMENT, kb.node()
		                .setProperty(kb.SOURCE, kb.node()
		                        .setProperty(kb.OBJECT, createMethods)
		                        .setProperty(kb.PROPERTY, kb.ARGUMENT))
		                .setProperty(kb.DESTINATION, kb.node()
		                        .setProperty(kb.OBJECT, finalCallback)
		                        .setProperty(kb.PROPERTY, kb.ARGUMENT)));
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
