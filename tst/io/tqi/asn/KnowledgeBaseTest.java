package io.tqi.asn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.concurrent.TimeUnit;

import org.junit.Test;

import io.reactivex.Single;

public class KnowledgeBaseTest {
	@Test
	public void testEmptySerialization() throws Exception {
		assertNotNull(TestUtil.serialize(new KnowledgeBase()));
	}

	@Test
	public void testPrint() {
		final Single<String> output;
		try (final KnowledgeBase kb = new KnowledgeBase()) {
			output = kb.rxOutput().replay().autoConnect(0).firstOrError().timeout(50, TimeUnit.MILLISECONDS);
			kb.invoke(kb.getOrCreateNode("print"), kb.getOrCreateValueNode("foo"), null);
			assertEquals("foo", output.blockingGet());
		}
	}
}
