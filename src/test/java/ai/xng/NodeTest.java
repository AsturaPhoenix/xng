package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Iterables;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.reactivex.subjects.CompletableSubject;
import lombok.val;

public class NodeTest {
  @Test
  public void testEmptySerialization() throws Exception {
    assertNotNull(TestUtil.serialize(new Node()));
  }

  @Test
  public void testValueSerialization() throws Exception {
    assertEquals("foo", TestUtil.serialize(new Node("foo")).value);
  }

  @Test
  public void testPropertySerialization() throws Exception {
    final Node oObj = new Node(), oPropLabel = new Node("foo"), oPropValue = new Node("bar");
    oObj.properties.put(oPropLabel, oPropValue);

    final Node sObj = TestUtil.serialize(oObj);
    final Entry<Node, Node> prop = Iterables.getOnlyElement(sObj.properties.entrySet());
    assertEquals("foo", prop.getKey().value);
    assertEquals("bar", prop.getValue().value);
  }

  private static void testActivation(final Node node) {
    val monitor = new EmissionMonitor<>(node.rxActivate());
    val context = Context.newDedicated();
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
    and.getSynapse().setCoefficient(a, .8f);
    and.getSynapse().setCoefficient(b, .8f);

    val context1 = Context.newDedicated();
    val activeMonitor1 = new EmissionMonitor<>(context1.rxActive());
    a.activate(context1);
    assertFalse(monitor.didEmit());
    assertEquals(Arrays.asList(false, true, false), activeMonitor1.emissions().toList().blockingGet());

    val context2 = Context.newDedicated();
    val activeMonitor2 = new EmissionMonitor<>(context2.rxActive());
    a.activate(context2);
    b.activate(context2);
    assertTrue(monitor.didEmit());
    assertEquals(false, activeMonitor2.emissions().blockingLast());
  }

  @Test
  public void testAndStability() {
    val a = new Node(), b = new Node(), and = new Node();
    and.getSynapse().setCoefficient(a, .8f);
    and.getSynapse().setCoefficient(b, .8f);

    for (int i = 0; i < 1000; ++i) {
      val context = Context.newDedicated();
      a.activate(context);
      b.activate(context);
      context.blockUntilIdle();
    }

    System.out.println(and.getSynapse());

    val monitor = new EmissionMonitor<>(and.rxActivate());
    val context1 = Context.newDedicated();
    a.activate(context1);
    context1.blockUntilIdle();
    assertFalse(monitor.didEmit());

    val context2 = Context.newDedicated();
    a.activate(context2);
    b.activate(context2);
    context2.blockUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testDisjointContexts() {
    val a = new Node(), b = new Node(), and = new Node();
    val monitor = new EmissionMonitor<>(and.rxActivate());
    and.getSynapse().setCoefficient(a, .8f);
    and.getSynapse().setCoefficient(b, .8f);
    a.activate(Context.newDedicated());
    b.activate(Context.newDedicated());
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testAndSerialization() throws Exception {
    Node[] nodes = { new Node(), new Node(), new Node() };
    nodes[2].getSynapse().setCoefficient(nodes[0], .8f);
    nodes[2].getSynapse().setCoefficient(nodes[1], .8f);

    nodes = TestUtil.serialize(nodes);

    val monitor = new EmissionMonitor<>(nodes[2].rxActivate());
    nodes[0].activate(Context.newDedicated());
    assertFalse(monitor.didEmit());
    val andContext = Context.newDedicated();
    nodes[0].activate(andContext);
    nodes[1].activate(andContext);
    assertTrue(monitor.didEmit());
  }

  /**
   * The refractory period of a neuron is the period after an activation where it
   * will not respond to another incoming pulse. This is implemented in the
   * synapse, which only triggers on edges.
   * 
   * Note that refractory periods do not apply to explicit
   * {@link Node#activate(Context)} calls.
   */
  @Test
  public void testAndRefractory() throws InterruptedException {
    val a = new Node(), b = new Node(), and = new Node();
    val monitor = new EmissionMonitor<>(and.rxActivate());
    and.getSynapse().setCoefficient(a, .8f);
    and.getSynapse().setCoefficient(b, .8f);
    val context = Context.newDedicated();
    a.activate(context);
    b.activate(context);
    b.activate(context);
    assertEquals(1, (long) monitor.emissions().count().blockingGet());
  }

  @Test
  public void testNearCoincidentInhibition() {
    val up = new Node(), down = new Node(), out = new Node();
    val monitor = new EmissionMonitor<>(out.rxActivate());
    out.getSynapse().setCoefficient(up, 1);
    out.getSynapse().setCoefficient(down, -1);
    val context = Context.newDedicated();
    down.activate(context);
    up.activate(context);
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testShortPullDown() throws Exception {
    val up = new Node(), down = new Node(), out = new Node();

    val activations = new ArrayList<Long>();
    final long beginning = System.currentTimeMillis();
    out.rxActivate().subscribe(a -> activations.add(a.timestamp - beginning));

    out.getSynapse().setCoefficient(up, 2.1f);
    out.getSynapse().setDecayPeriod(up, 1000);
    out.getSynapse().setCoefficient(down, -3);
    out.getSynapse().setDecayPeriod(down, 500);
    val context = Context.newDedicated();
    down.activate(context);
    up.activate(context);
    context.blockUntilIdle();

    assertEquals(1, activations.size(), activations.toString());
    assertThat(activations.get(0)).isGreaterThan(400);
  }

  @Test
  public void testBlocking() throws Exception {
    val node = new Node();
    val sync = CompletableSubject.create();
    node.setOnActivate(context -> sync);
    val monitor = new EmissionMonitor<>(node.rxActivate());
    val context = Context.newDedicated();
    node.activate(context);
    assertFalse(monitor.didEmit());
    assertEquals(Arrays.asList(true), context.rxActive().take(500, TimeUnit.MILLISECONDS).toList().blockingGet());
    sync.onComplete();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testBlockingThenActivate() throws Exception {
    val a = new Node(), b = new Node();
    a.then(b);
    val sync = CompletableSubject.create();
    a.setOnActivate(c -> sync);
    val monitor = new EmissionMonitor<>(b.rxActivate());
    val context = Context.newDedicated();
    val activeMonitor = new EmissionMonitor<>(context.rxActive());
    a.activate(context);
    assertFalse(monitor.didEmit());
    sync.onComplete();
    assertTrue(monitor.didEmit());
    assertEquals(Arrays.asList(false, true, false), activeMonitor.emissions().toList().blockingGet());
  }

  @Test
  public void testThenOnActivate() {
    val a = new Node(), b = new Node();
    a.then(b);
    val sync = CompletableSubject.create();
    b.setOnActivate(c -> {
      sync.onComplete();
      return sync;
    });
    a.activate(Context.newDedicated());
    assertTimeoutPreemptively(Duration.ofSeconds(1), (Executable) sync::blockingAwait);
  }
}
