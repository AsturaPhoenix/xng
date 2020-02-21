package ai.xng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
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
    val context = new Context(Node::new);
    val activeMonitor = new EmissionMonitor<>(context.rxActive());
    node.activate(context);
    assertSame(context, monitor.emissions().blockingFirst().context);
    assertEquals(Arrays.asList(false, true, false), activeMonitor.emissions().toList().blockingGet());
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
  public void testAnd() throws Exception {
    val a = new Node(), b = new Node(), and = new Node();
    val monitor = new EmissionMonitor<>(and.rxActivate());
    and.synapse.setCoefficient(a, .8f);
    and.synapse.setCoefficient(b, .8f);

    val context1 = new Context(Node::new);
    val activeMonitor1 = new EmissionMonitor<>(context1.rxActive());
    a.activate(context1);
    assertFalse(monitor.didEmit());
    assertEquals(Arrays.asList(false, true, false), activeMonitor1.emissions().toList().blockingGet());

    val context2 = new Context(Node::new);
    val activeMonitor2 = new EmissionMonitor<>(context2.rxActive());
    a.activate(context2);
    b.activate(context2);
    assertTrue(monitor.didEmit());
    assertEquals(false, activeMonitor2.emissions().blockingLast());
  }

  @Test
  public void testDisjointContexts() {
    val a = new Node(), b = new Node(), and = new Node();
    val monitor = new EmissionMonitor<>(and.rxActivate());
    and.synapse.setCoefficient(a, .8f);
    and.synapse.setCoefficient(b, .8f);
    a.activate(new Context(Node::new));
    b.activate(new Context(Node::new));
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testAndSerialization() throws Exception {
    Node[] nodes = { new Node(), new Node(), new Node() };
    nodes[2].synapse.setCoefficient(nodes[0], .8f);
    nodes[2].synapse.setCoefficient(nodes[1], .8f);

    nodes = TestUtil.serialize(nodes);

    val monitor = new EmissionMonitor<>(nodes[2].rxActivate());
    nodes[0].activate(new Context(Node::new));
    assertFalse(monitor.didEmit());
    val andContext = new Context(Node::new);
    nodes[0].activate(andContext);
    nodes[1].activate(andContext);
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testRefractory() {
    val node = new Node();
    node.setRefractory(1000);
    val monitor = new EmissionMonitor<>(node.rxActivate());
    val context = new Context(Node::new);
    node.activate(context);
    node.activate(context);
    assertEquals(1, (long) monitor.emissions().count().blockingGet());
  }

  @Test
  public void testAndRefractory() throws InterruptedException {
    val a = new Node(), b = new Node(), and = new Node();
    val monitor = new EmissionMonitor<>(and.rxActivate());
    and.synapse.setCoefficient(a, .6f);
    and.synapse.setCoefficient(b, .5f);
    and.setRefractory(10000);
    val context = new Context(Node::new);
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
    val context = new Context(Node::new);
    down.activate(context);
    up.activate(context);
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testShortPullDown() throws Exception {
    val up = new Node(), down = new Node(), out = new Node();

    val activations = new ArrayList<Long>();
    long beginning = System.currentTimeMillis();
    out.rxActivate().subscribe(a -> activations.add(a.timestamp - beginning));

    out.synapse.setCoefficient(up, 2.1f);
    out.synapse.setDecayPeriod(up, 1000);
    out.synapse.setCoefficient(down, -3);
    out.synapse.setDecayPeriod(down, 500);
    val context = new Context(Node::new);
    down.rxActivate().subscribe(a -> up.activate(context));
    down.activate(context);
    context.rxActive().filter(active -> !active).blockingFirst();

    assertEquals(activations.toString(), 1, activations.size());
    assertTrue(activations.get(0) + " !> 400", activations.get(0) > 400);
  }

  @Test
  public void testDebounceEdge() throws Exception {
    for (int i = 0; i < 100; i++) {
      val a = new Node(), b = new Node(), and = new Node();
      val monitor = new EmissionMonitor<>(and.rxActivate());
      and.synapse.setCoefficient(a, .6f);
      and.synapse.setCoefficient(b, .6f);
      val context = new Context(Node::new);
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
    val context = new Context(Node::new);
    node.activate(context);
    assertFalse(monitor.didEmit());
    assertTrue(context.rxActive().blockingFirst());
    subject.onComplete();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testBlockingThenActivate() throws Exception {
    val a = new Node(), b = new Node();
    a.then(b);
    final PublishSubject<Void> subject = PublishSubject.create();
    a.setOnActivate(c -> subject.ignoreElements().blockingAwait());
    val monitor = new EmissionMonitor<>(b.rxActivate());
    val context = new Context(Node::new);
    val activeMonitor = new EmissionMonitor<>(context.rxActive());
    a.activate(context);
    assertFalse(monitor.didEmit());
    subject.onComplete();
    assertTrue(monitor.didEmit());
    assertEquals(Arrays.asList(false, true, false), activeMonitor.emissions().toList().blockingGet());
  }

  @Test(timeout = 1000)
  public void testThenOnActivate() {
    val a = new Node(), b = new Node();
    a.then(b);
    final PublishSubject<Void> subject = PublishSubject.create();
    b.setOnActivate(c -> subject.onComplete());
    a.activate(new Context(Node::new));
    assertTrue(subject.isEmpty().blockingGet());
  }
}
