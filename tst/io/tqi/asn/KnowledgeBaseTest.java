package io.tqi.asn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

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
		KnowledgeBase kb = new KnowledgeBase();
		setUpPropGet(kb);
		kb = TestUtil.serialize(kb);
		assertPropGet(kb);
	}
}
