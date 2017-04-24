package io.tqi.asn;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public class KnowledgeBaseTest {
	@Test
	public void testEmptySerialization() throws Exception {
		assertNotNull(TestUtil.serialize(new KnowledgeBase()));
	}
}
