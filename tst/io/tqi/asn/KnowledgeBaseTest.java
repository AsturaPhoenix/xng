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
}
