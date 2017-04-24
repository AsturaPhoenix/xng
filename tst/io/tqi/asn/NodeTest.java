package io.tqi.asn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

	private static class EmissionMonitor {
		private static final Object DID_NOT_ACTIVATE = new Object();

		Observable<?> source;
		Observable<Object> monitor;

		EmissionMonitor(final Observable<?> source) {
			this.source = source;
			reset();
		}

		void reset() {
			monitor = source.replay().autoConnect(0).map(x -> this);
		}

		boolean didEmit() {
			boolean didEmit = monitor.takeUntil(Observable.timer(50, TimeUnit.MILLISECONDS))
					.blockingFirst(DID_NOT_ACTIVATE) != DID_NOT_ACTIVATE;
			reset();
			return didEmit;
		}
	}

	private static void testActivation(final Node node) {
		final EmissionMonitor monitor = new EmissionMonitor(node.rxActivate());
		node.activate();
		assertTrue(monitor.didEmit());
	}

	@Test
	public void testActivation() {
		testActivation(new Node());
	}

	@Test
	public void testPostSerializeActivation() throws Exception {
		testActivation(TestUtil.serialize(new Node()));
	}

	@Test
	public void testRefractoryChange() {
		final Node node = new Node();
		final EmissionMonitor monitor = new EmissionMonitor(node.rxChange());
		node.setRefractory(42);
		assertTrue(monitor.didEmit());
	}

	@Test
	public void testPostSerializeSynapseChange() throws Exception {
		final Node node = TestUtil.serialize(new Node());
		final EmissionMonitor monitor = new EmissionMonitor(node.rxChange());
		node.getSynapse().setDecayRate(new Node(), 1);
		assertTrue(monitor.didEmit());
	}

	@Test
	public void testAnd() {
		final Node a = new Node(), b = new Node(), and = new Node();
		final EmissionMonitor monitor = new EmissionMonitor(and.rxActivate());
		and.getSynapse().setCoefficient(a, .8f);
		and.getSynapse().setCoefficient(b, .8f);
		a.activate();
		assertFalse(monitor.didEmit());
		b.activate();
		assertTrue(monitor.didEmit());
	}

	@Test
	public void testAndSerialization() throws Exception {
		Node[] nodes = { new Node(), new Node(), new Node() };
		nodes[2].getSynapse().setCoefficient(nodes[0], .8f);
		nodes[2].getSynapse().setCoefficient(nodes[1], .8f);

		nodes = TestUtil.serialize(nodes);

		final EmissionMonitor monitor = new EmissionMonitor(nodes[2].rxActivate());
		nodes[0].activate();
		assertFalse(monitor.didEmit());
		nodes[1].activate();
		assertTrue(monitor.didEmit());
	}
}
