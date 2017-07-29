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
		kb.invoke(kb.getOrCreateNode("print"), kb.getOrCreateValueNode("foo"), null);
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
		kb.getOrCreateNode("roses").setProperty(kb.getOrCreateNode("color"), kb.getOrCreateValueNode("red"));

		final Node arg = kb.createNode();
		arg.setProperty(kb.getOrCreateNode("object"), kb.getOrCreateNode("roses"));
		arg.setProperty(kb.getOrCreateNode("property"), kb.getOrCreateNode("color"));

		final Node invocation = kb.getOrCreateNode("roses are");
		invocation.setProperty(kb.EXECUTE, kb.getOrCreateNode("getProperty"));
		invocation.setProperty(kb.ARGUMENT, arg);
	}

	private static void assertPropGet(final KnowledgeBase kb) {
		final EmissionMonitor<String> monitor = new EmissionMonitor<>(kb.rxOutput());
		kb.invoke(kb.getOrCreateNode("print"), kb.getOrCreateNode("roses are"), null);
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
		final Node content = kb.createNode(ImmutableNodeList.from(kb.getOrCreateValueNode("foo")));

		final Node callback = kb.createNode();
		final EmissionMonitor<?> monitor = new EmissionMonitor<>(callback.rxActivate());
		kb.invoke(kb.getOrCreateNode("iterator"), content, callback);
		assertTrue(monitor.didEmit());
		final Node iterator = callback.getProperty(kb.ARGUMENT);
		kb.indexNode("test iterator", iterator);
		final Node onMove = iterator.getProperty(kb.getOrCreateNode("onMove"));
		onMove.setProperty(kb.EXECUTE, kb.getOrCreateNode("print"));
	}

	private static void assertIterator(final KnowledgeBase kb) {
		final Node testIterator = kb.getOrCreateNode("test iterator");
		final Node onMove = testIterator.getProperty(kb.getOrCreateNode("onMove"));

		final EmissionMonitor<String> outputMonitor = new EmissionMonitor<>(kb.rxOutput());
		final EmissionMonitor<?> onMoveMonitor = new EmissionMonitor<>(onMove.rxActivate());
		testIterator.getProperty(kb.getOrCreateNode("forward")).activate();
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
			final Node fib = kb.createNode(), a = kb.createNode(), b = kb.createNode(), c = kb.createNode();
			fib.setProperty(a, kb.getOrCreateValueNode(0));
			fib.setProperty(b, kb.getOrCreateValueNode(1));
			final Node getA = kb.createNode(), getAArgs = kb.createNode();
			getA.setProperty(kb.EXECUTE, kb.getOrCreateNode("getProperty"));
			getA.setProperty(kb.ARGUMENT, getAArgs);
			getAArgs.setProperty(kb.getOrCreateNode("object"), fib);
			getAArgs.setProperty(kb.getOrCreateNode("property"), a);
			final Node fibAdd = kb.createNode(), fibAddArgs = kb.createNode();
			fibAdd.setProperty(kb.EXECUTE, kb.getOrCreateNode("math.add"));
			fibAdd.setProperty(kb.ARGUMENT, fibAddArgs);
			fibAddArgs.setProperty(kb.arg(1), getA);
		}
	}
}
