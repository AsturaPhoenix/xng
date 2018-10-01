package io.tqi.ekg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Map.Entry;
import com.google.common.collect.Iterables;
import org.junit.Test;
import io.reactivex.subjects.PublishSubject;
import lombok.val;

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
    oObj.properties.put(oPropLabel, oPropValue);

    final Node sObj = TestUtil.serialize(oObj);
    final Entry<Node, Node> prop = Iterables.getOnlyElement(sObj.properties.entrySet());
    assertEquals("foo", prop.getKey().getValue());
    assertEquals("bar", prop.getValue().getValue());
  }

  private static void testActivation(final Node node) {
    val monitor = new EmissionMonitor<>(node.rxActivate());
    val context = new Context();
    node.activate(context);
    assertSame(context, monitor.emissions().blockingFirst().context);
  }

  @Test
  public void testActivation() {
    testActivation(new Node());
  }

  @Test
  public void testActivationAfterSerialization() throws Exception {
    testActivation(TestUtil.serialize(new Node()));
  }

  @Test
  public void testAnd() {
    val a = new Node(), b = new Node(), and = new Node();
    val monitor = new EmissionMonitor<>(and.rxActivate());
    and.synapse.setCoefficient(a, .8f);
    and.synapse.setCoefficient(b, .8f);
    a.activate(new Context());
    assertFalse(monitor.didEmit());
    val andContext = new Context();
    a.activate(andContext);
    b.activate(andContext);
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testDisjointContexts() {
    val a = new Node(), b = new Node(), and = new Node();
    val monitor = new EmissionMonitor<>(and.rxActivate());
    and.synapse.setCoefficient(a, .8f);
    and.synapse.setCoefficient(b, .8f);
    a.activate(new Context());
    b.activate(new Context());
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testAndSerialization() throws Exception {
    Node[] nodes = {new Node(), new Node(), new Node()};
    nodes[2].synapse.setCoefficient(nodes[0], .8f);
    nodes[2].synapse.setCoefficient(nodes[1], .8f);

    nodes = TestUtil.serialize(nodes);

    val monitor = new EmissionMonitor<>(nodes[2].rxActivate());
    nodes[0].activate(new Context());
    assertFalse(monitor.didEmit());
    val andContext = new Context();
    nodes[0].activate(andContext);
    nodes[1].activate(andContext);
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testRefractory() {
    val node = new Node();
    node.setRefractory(1000);
    val monitor = new EmissionMonitor<>(node.rxActivate());
    val context = new Context();
    node.activate(context);
    node.activate(context);
    assertEquals(1, monitor.emissions().toList().blockingGet().size());
  }

  @Test
  public void testAndRefractory() throws InterruptedException {
    val a = new Node(), b = new Node(), and = new Node();
    val monitor = new EmissionMonitor<>(and.rxActivate());
    and.synapse.setCoefficient(a, .6f);
    and.synapse.setCoefficient(b, .5f);
    and.setRefractory(10000);
    val context = new Context();
    a.activate(context);
    b.activate(context);
    assertTrue(monitor.didEmit());
    b.activate(context);
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testNearCoincidentInhibition() {
    val up = new Node(), down = new Node(), out = new Node();
    val monitor = new EmissionMonitor<>(out.rxActivate());
    out.synapse.setCoefficient(up, 1);
    out.synapse.setCoefficient(down, -1);
    val context = new Context();
    down.activate(context);
    up.activate(context);
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testShortPullDown() throws Exception {
    val up = new Node(), down = new Node(), out = new Node();
    val monitor = new EmissionMonitor<>(out.rxActivate());
    out.synapse.setCoefficient(up, 2);
    out.synapse.setDecayPeriod(up, 1000);
    out.synapse.setCoefficient(down, -3);
    out.synapse.setDecayPeriod(down, 300);
    val context = new Context();
    down.activate(context);
    up.activate(context);
    assertFalse(monitor.didEmit());
    Thread.sleep(500);
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testDebounceEdge() throws Exception {
    for (int i = 0; i < 100; i++) {
      val a = new Node(), b = new Node(), and = new Node();
      val monitor = new EmissionMonitor<>(and.rxActivate());
      and.synapse.setCoefficient(a, .6f);
      and.synapse.setCoefficient(b, .6f);
      val context = new Context();
      a.activate(context);
      Thread.sleep(Synapse.DEBOUNCE_PERIOD);
      b.activate(context);
      assertTrue(monitor.didEmit());
    }
  }

  @Test
  public void testBlocking() throws Exception {
    val node = new Node();
    final PublishSubject<Void> subject = PublishSubject.create();
    node.setOnActivate(context -> subject.ignoreElements().blockingAwait());
    val monitor = new EmissionMonitor<>(node.rxActivate());
    node.activate(new Context());
    Thread.sleep(250);
    assertFalse(monitor.didEmit());
    subject.onComplete();
    Thread.sleep(250);
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testBlockingThenActivate() throws Exception {
    val a = new Node(), b = new Node();
    a.then(b);
    final PublishSubject<Void> subject = PublishSubject.create();
    a.setOnActivate(c -> subject.ignoreElements().blockingAwait());
    val monitor = new EmissionMonitor<>(b.rxActivate());
    a.activate(new Context());
    Thread.sleep(250);
    subject.onComplete();
    assertTrue(monitor.didEmit());
  }

  @Test(timeout = 1000)
  public void testThenOnActivate() {
    val a = new Node(), b = new Node();
    a.then(b);
    final PublishSubject<Void> subject = PublishSubject.create();
    b.setOnActivate(c -> subject.onComplete());
    a.activate(new Context());
    assertTrue(subject.isEmpty().blockingGet());
  }
}
