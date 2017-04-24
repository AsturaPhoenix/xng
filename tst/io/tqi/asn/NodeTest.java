package io.tqi.asn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.common.collect.Iterables;

import io.reactivex.Observable;

public class NodeTest {
	@Test
	public void testEmptySerialization() throws Exception {
		assertNotNull(TestUtil.serialize(new Node()));
	}

	@Test
	public void testValueSerialization() throws Exception {
		assertEquals("foo", TestUtil.serialize(new Node("foo")).getValue());
	}

	@Test
	public void testPropertySerialization() throws Exception {
		final Node oObj = new Node(), oPropLabel = new Node("foo"), oPropValue = new Node("bar");
		oObj.setProperty(oPropLabel, oPropValue);

		final Node sObj = TestUtil.serialize(oObj);
		final Entry<Node, Node> prop = Iterables.getOnlyElement(sObj.getProperties().entrySet());
		assertEquals("foo", prop.getKey().getValue());
		assertEquals("bar", prop.getValue().getValue());
	}

	private void testActivation(final Node node) {
		final Observable<?> rxActivate = node.rxActivate().replay().autoConnect(0);
		node.activate();
		rxActivate.takeUntil(Observable.timer(50, TimeUnit.MILLISECONDS)).blockingFirst();
	}

	@Test
	public void testActivation() {
		testActivation(new Node());
	}

	@Test
	public void testPostSerializeActivation() throws Exception {
		testActivation(TestUtil.serialize(new Node()));
	}
}
