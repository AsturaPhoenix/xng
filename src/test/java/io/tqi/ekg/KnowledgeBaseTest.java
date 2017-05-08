package io.tqi.ekg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.tqi.ekg.value.ImmutableNodeList;
import io.tqi.ekg.value.StringValue;

public class KnowledgeBaseTest {
	@Test
	public void testEmptySerialization() throws Exception {
		assertNotNull(TestUtil.serialize(new KnowledgeBase()));
	}

	private static void testPrint(final KnowledgeBase kb) {
		final EmissionMonitor<String> monitor = new EmissionMonitor<>(kb.rxOutput());
		kb.invoke(kb.node("print"), kb.valueNode("foo"), null);
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
		invocation.setProperty(kb.EXECUTE, kb.node("getProperty"));
		invocation.setProperty(kb.ARGUMENT, arg);
	}

	private static void assertPropGet(final KnowledgeBase kb) {
		final EmissionMonitor<String> monitor = new EmissionMonitor<>(kb.rxOutput());
		kb.invoke(kb.node("print"), kb.node("roses are"), null);
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

	private static void setUpIterator(final KnowledgeBase kb) {
		final Node content = kb.node(ImmutableNodeList.from(kb.valueNode("foo")));

		final Node callback = kb.node();
		final EmissionMonitor<?> monitor = new EmissionMonitor<>(callback.rxActivate());
		kb.invoke(kb.node("iterator"), content, callback);
		assertTrue(monitor.didEmit());
		final Node iterator = callback.getProperty(kb.ARGUMENT);
		kb.indexNode("test iterator", iterator);
		final Node onMove = iterator.getProperty(kb.node("onMove"));
		onMove.setProperty(kb.EXECUTE, kb.node("print"));
	}

	private static void assertIterator(final KnowledgeBase kb) {
		final Node testIterator = kb.node("test iterator");
		final Node onMove = testIterator.getProperty(kb.node("onMove"));

		final EmissionMonitor<String> outputMonitor = new EmissionMonitor<>(kb.rxOutput());
		final EmissionMonitor<?> onMoveMonitor = new EmissionMonitor<>(onMove.rxActivate());
		testIterator.getProperty(kb.node("forward")).activate();
		assertTrue(onMoveMonitor.didEmit());
		assertEquals(new StringValue("foo"), onMove.getProperty(kb.ARGUMENT).getValue());
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

	@Test
	public void testFibbonacci() {
		try (final KnowledgeBase kb = new KnowledgeBase()) {
			final Node fib = kb.node(), a = kb.node(), b = kb.node(), c = kb.node();
			fib.setProperty(a, kb.valueNode(0));
			fib.setProperty(b, kb.valueNode(1));

			final Node print = kb.node(), printArg = kb.node();
			print.setProperty(kb.EXECUTE, kb.node("print"));
			print.setProperty(kb.ARGUMENT, printArg);
			printArg.setProperty(kb.EXECUTE, kb.node("getProperty"));
		}
	}
}
