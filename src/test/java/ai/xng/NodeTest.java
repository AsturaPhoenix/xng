package ai.xng;

import static ai.xng.TestUtil.threadPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.common.collect.Iterables;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.reactivex.Completable;
import io.reactivex.subjects.CompletableSubject;
import lombok.RequiredArgsConstructor;
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

  @Test
  public void testUnserializableValue() throws Exception {
    assertNull(TestUtil.serialize(new Node(new Object())).getValue());
  }

  private static void testActivation(final Node node) {
    val monitor = new EmissionMonitor<>(node.rxActivate());
    val context = Context.newWithExecutor(threadPool);
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
    val a = new Node(), b = new Node(), and = new SynapticNode();
    val monitor = new SynchronousEmissionMonitor<>(and.rxActivate());
    and.conjunction(a, b);

    {
      val context = Context.newWithExecutor(threadPool);
      a.activate(context);
      context.blockUntilIdle();
      assertFalse(monitor.didEmit());
    }

    {
      val context = Context.newWithExecutor(threadPool);
      a.activate(context);
      b.activate(context);
      context.blockUntilIdle();
      assertTrue(monitor.didEmit());
    }
  }

  @Test
  public void testAndStability() {
    val a = new Node(), b = new Node(), and = new SynapticNode();
    and.conjunction(a, b);

    for (int i = 0; i < 1000; ++i) {
      val context = Context.newWithExecutor(threadPool);
      a.activate(context);
      b.activate(context);
      context.blockUntilIdle();
    }

    val monitor = new SynchronousEmissionMonitor<>(and.rxActivate());
    {
      val context = Context.newWithExecutor(threadPool);
      a.activate(context);
      context.blockUntilIdle();
      assertFalse(monitor.didEmit());
    }

    {
      val context = Context.newWithExecutor(threadPool);
      a.activate(context);
      b.activate(context);
      context.blockUntilIdle();
      assertTrue(monitor.didEmit());
    }
  }

  @Test
  public void test4And() {
    val a = new Node(), b = new Node(), c = new Node(), d = new Node(), and = new SynapticNode();
    and.conjunction(a, b, c, d);
    val monitor = new SynchronousEmissionMonitor<>(and.rxActivate());

    for (int i = 1; i <= 1000; ++i) {
      {
        val context = Context.newWithExecutor(threadPool);
        a.activate(context);
        b.activate(context);
        c.activate(context);
        d.activate(context);
        context.blockUntilIdle();
        assertTrue(monitor.didEmit(), String.format("4-conjunction activation failed at iteration %s.", i));
      }
      {
        val context = Context.newWithExecutor(threadPool);
        a.activate(context);
        b.activate(context);
        c.activate(context);
        context.blockUntilIdle();
        assertFalse(monitor.didEmit(), String.format("4-conjunction spurious activation at iteration %s.", i));
      }
    }
  }

  @Test
  public void testDisjointContexts() {
    val a = new Node(), b = new Node(), and = new SynapticNode();
    val monitor = new EmissionMonitor<>(and.rxActivate());
    and.conjunction(a, b);
    a.activate(Context.newWithExecutor(threadPool));
    b.activate(Context.newWithExecutor(threadPool));
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testAndSerialization() throws Exception {
    SynapticNode[] nodes = { new SynapticNode(), new SynapticNode(), new SynapticNode() };
    nodes[2].conjunction(nodes[0], nodes[1]);

    nodes = TestUtil.serialize(nodes);

    val monitor = new EmissionMonitor<>(nodes[2].rxActivate());
    nodes[0].activate(Context.newWithExecutor(threadPool));
    assertFalse(monitor.didEmit());
    val andContext = Context.newWithExecutor(threadPool);
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
    val a = new Node(), b = new Node(), and = new SynapticNode();
    val monitor = new EmissionMonitor<>(and.rxActivate());
    and.conjunction(a, b);
    val context = Context.newWithExecutor(threadPool);
    a.activate(context);
    b.activate(context);
    b.activate(context);
    assertEquals(1, (long) monitor.emissions().count().blockingGet());
  }

  @Test
  public void testNearCoincidentInhibition() {
    val up = new Node(), down = new Node(), out = new SynapticNode();
    val monitor = new EmissionMonitor<>(out.rxActivate());
    out.synapse.setCoefficient(up, 1);
    out.synapse.setCoefficient(down, -1);
    val context = Context.newWithExecutor(threadPool);
    down.activate(context);
    up.activate(context);
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testShortPullDown() throws Exception {
    val up = new Node(), down = new Node(), out = new SynapticNode();

    val activations = new ArrayList<Long>();
    final long beginning = System.currentTimeMillis();
    out.rxActivate().subscribe(a -> activations.add(a.timestamp - beginning));

    out.synapse.setCoefficient(up, 2.1f);
    out.synapse.setDecayPeriod(up, 1000);
    out.synapse.setCoefficient(down, -3);
    out.synapse.setDecayPeriod(down, 500);
    val context = Context.newWithExecutor(threadPool);
    down.activate(context);
    up.activate(context);
    context.blockUntilIdle();

    assertEquals(1, activations.size(), activations.toString());
    assertThat(activations.get(0)).isGreaterThan(400);
  }

  @RequiredArgsConstructor
  private static class TestNode extends SynapticNode {
    private static final long serialVersionUID = 1L;

    final Supplier<Completable> onActivate;

    @Override
    protected Completable onActivate(Context context) {
      return onActivate.get();
    }
  }

  @Test
  public void testBlocking() throws Exception {
    val sync = CompletableSubject.create();
    val node = new TestNode(() -> sync);
    val monitor = new EmissionMonitor<>(node.rxActivate());
    val context = Context.newWithExecutor(threadPool);
    node.activate(context);
    assertFalse(monitor.didEmit());
    assertEquals(Arrays.asList(true), context.rxActive().take(500, TimeUnit.MILLISECONDS).toList().blockingGet());
    sync.onComplete();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testBlockingThenActivate() throws Exception {
    val sync = CompletableSubject.create();
    val a = new Node(), b = new TestNode(() -> sync);
    a.then(b);
    val monitor = new EmissionMonitor<>(b.rxActivate());
    val context = Context.newWithExecutor(threadPool);
    val activeMonitor = new EmissionMonitor<>(context.rxActive());
    a.activate(context);
    assertFalse(monitor.didEmit());
    sync.onComplete();
    assertTrue(monitor.didEmit());
    assertEquals(Arrays.asList(false, true, false), activeMonitor.emissions().toList().blockingGet());
  }

  @Test
  public void testThenOnActivate() {
    val sync = CompletableSubject.create();
    val a = new Node(), b = new TestNode(() -> {
      sync.onComplete();
      return sync;
    });
    a.then(b);
    a.activate(Context.newWithExecutor(threadPool));
    assertTimeoutPreemptively(Duration.ofSeconds(1), (Executable) sync::blockingAwait);
  }

  @Test
  public void testSynapseGc() throws Exception {
    val posterior = new SynapticNode();

    val gc = new GcFixture(posterior);

    for (int i = 0; i < 1000; ++i) {
      new Node().then(posterior);
    }

    gc.assertNoGrowth();
  }
}
