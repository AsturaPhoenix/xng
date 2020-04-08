package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import ai.xng.KnowledgeBase.BuiltIn;
import ai.xng.KnowledgeBase.Common;
import io.reactivex.Completable;
import io.reactivex.subjects.CompletableSubject;
import lombok.val;

public class KnowledgeBaseTest {
  private static final Duration TIMEOUT = Duration.ofMillis(500);

  @Test
  public void testEmptySerialization() throws Exception {
    assertNotNull(TestUtil.serialize(new KnowledgeBase()));
  }

  private static void testPrint(final KnowledgeBase kb) throws Exception {
    val monitor = new EmissionMonitor<>(kb.rxOutput());

    val context = kb.newContext();
    context.node.properties.put(kb.node(Common.value), kb.node("foo"));
    kb.node(BuiltIn.print).activate(context);
    assertEquals("foo", monitor.emissions().blockingFirst());
    // ensure that the context closes
    context.continuation().get(1, TimeUnit.SECONDS);
  }

  @Test
  public void testPrint() throws Exception {
    testPrint(new KnowledgeBase());
  }

  @Test
  public void testPrintAfterSerialization() throws Exception {
    testPrint(TestUtil.serialize(new KnowledgeBase()));
  }

  @Test
  public void testInvocationUtility() {
    try (val kb = new KnowledgeBase()) {
      val invocation = kb.new InvocationNode(kb.node(BuiltIn.print));
      invocation.literal(kb.node(Common.value), kb.node("foo"));
      assertThat(invocation.properties).hasSize(1).extractingByKey(kb.node(Common.literal))
          .satisfies(node -> assertThat(node.properties).containsOnly(entry(kb.node(Common.value), kb.node("foo"))));
    }
  }

  private static void setUpPropGet(final KnowledgeBase kb) {
    final Node roses = kb.node("roses"), color = kb.node("color");
    roses.properties.put(color, kb.node("red"));

    val rosesAre = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).literal(kb.node(Common.object), roses)
        .literal(kb.node(Common.name), color);
    val print = kb.new InvocationNode(kb.node(BuiltIn.print)).transform(kb.node(Common.value), rosesAre);

    rosesAre.then(print);

    final Node index = kb.node("index");
    index.properties.put(kb.node("rosesAre"), rosesAre);
    index.properties.put(kb.node("print"), print);
  }

  private static void assertPropGet(final KnowledgeBase kb) {
    final Node index = kb.node("index");
    final Node rosesAre = index.properties.get(kb.node("rosesAre")), print = index.properties.get(kb.node("print"));

    val sanity1 = new EmissionMonitor<>(rosesAre.rxActivate()),
        sanity2 = new EmissionMonitor<>(kb.node(BuiltIn.getProperty).rxActivate()),
        sanity3 = new EmissionMonitor<>(kb.node(BuiltIn.print).rxActivate());
    val valueMonitor = new EmissionMonitor<>(kb.rxOutput());

    final Context context = kb.newContext();
    context.fatalOnExceptions();

    rosesAre.activate(context);
    context.blockUntilIdle(Duration.ofSeconds(5));
    assertTrue(sanity1.didEmit());
    assertTrue(sanity2.didEmit());
    try {
      assertTrue(sanity3.didEmit());
    } catch (final Throwable t) {
      final Synapse synapse = ((SynapticNode) print).synapse;
      System.err.printf("Invocation synapse:\n%s\n\nRecent evaluations:\n%s\n\n", synapse,
          synapse.getRecentEvaluations(context, System.currentTimeMillis() - 500));
      throw t;
    }
    assertEquals("red", valueMonitor.emissions().blockingFirst());
  }

  @Test
  public void testPrintProp() {
    try (val kb = new KnowledgeBase()) {
      setUpPropGet(kb);
      assertPropGet(kb);
    }
  }

  @Test
  public void testPrintReliability() throws InterruptedException {
    try (val kb = new KnowledgeBase()) {
      setUpPropGet(kb);
      for (int i = 0; i < 500; i++) {
        try {
          assertPropGet(kb);
        } catch (final Throwable t) {
          System.err.printf("Failed in iteration %s.\n", i);
          throw t;
        }
      }
    }
  }

  @Test
  public void testNodeGc() throws Exception {
    try (val kb = new KnowledgeBase()) {
      val gc = new GcFixture(kb);

      for (int i = 0; i < 1000; ++i) {
        kb.new InvocationNode(kb.node(BuiltIn.print));
      }

      gc.assertNoGrowth();
    }
  }

  @Test
  public void testActivationGc() throws Exception {
    val node = new Node();

    try (val kb = new KnowledgeBase()) {
      {
        val context = kb.newContext();
        node.activate(context);
        context.blockUntilIdle();
      }

      val gc = new GcFixture(kb);

      for (int i = 0; i < 1000; ++i) {
        val context = kb.newContext();
        node.activate(context);
        context.blockUntilIdle();
      }

      gc.assertNoGrowth();
    }
  }

  @Test
  public void testSynapseActivationGc() throws Exception {
    val posterior = new SynapticNode();

    try (val kb = new KnowledgeBase()) {
      {
        val prior = new Node();
        prior.then(posterior);

        val context = kb.newContext();
        prior.activate(context);
        context.blockUntilIdle();
      }

      val gc = new GcFixture(kb);

      for (int i = 0; i < 1000; ++i) {
        val prior = new Node();
        prior.then(posterior);

        val context = kb.newContext();
        prior.activate(context);
        context.blockUntilIdle();
      }

      gc.assertNoGrowth();
    }
  }

  @Test
  public void testInvocationGc() throws Exception {
    try (val kb = new KnowledgeBase()) {
      setUpPropGet(kb);

      {
        val context = kb.newContext();
        kb.node("roses are").activate(context);
        context.blockUntilIdle();
      }

      val gc = new GcFixture(kb);

      for (int i = 0; i < 1000; ++i) {
        val context = kb.newContext();
        kb.node("roses are").activate(context);
        context.blockUntilIdle();
      }

      gc.assertSize("2 * %d", 2 * gc.initialSize);
    }
  }

  @Test
  public void testPrintPropAfterSerialization() throws Exception {
    try (val kb = new KnowledgeBase()) {
      setUpPropGet(kb);
      assertPropGet(TestUtil.serialize(kb));
    }
  }

  @Test
  public void testException() {
    try (val kb = new KnowledgeBase()) {
      final Node exceptionHandler = new Node();
      val monitor = new EmissionMonitor<>(exceptionHandler.rxActivate());

      final Node invocation = kb.new InvocationNode(kb.node(BuiltIn.print)).exceptionHandler(exceptionHandler);
      val context = kb.newContext();
      invocation.activate(context);
      context.blockUntilIdle();

      val activation = monitor.emissions().blockingFirst();
      final Node exception = activation.context.node.properties.get(kb.node(Common.exception));
      // TODO(rosswang): Once we support node-space stack traces, the deepest frame in
      // this case may be BuiltIn.print, followed by invocation.
      assertSame(invocation, exception.properties.get(kb.node(Common.source)));
    }
  }

  /**
   * Ensures that the invocation of a trivial custom subroutine completes.
   */
  @Test
  public void testCustomInvocationCompletes() {
    try (val kb = new KnowledgeBase()) {
      val context = kb.newContext();
      kb.new InvocationNode(new Node()).activate(context);
      context.blockUntilIdle(TIMEOUT);
    }
  }

  @Test
  public void testParentContextInheritsChildActivity() {
    try (val kb = new KnowledgeBase()) {
      val sync = CompletableSubject.create();
      val block = new SynapticNode() {
        private static final long serialVersionUID = 1419012533053020615L;

        @Override
        protected Completable onActivate(Context context) {
          return sync;
        }
      };
      val returnToParent = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).literal(kb.node(Common.name),
          kb.node(Common.returnValue));
      returnToParent.then(block);

      val invoke = kb.new InvocationNode(returnToParent);
      val end = new SynapticNode();
      val monitor = new EmissionMonitor<>(end.rxActivate());
      invoke.then(end);

      val context = kb.newContext();
      invoke.activate(context);
      assertTrue(monitor.didEmit());
      assertEquals(Arrays.asList(true), context.rxActive().take(500, TimeUnit.MILLISECONDS).toList().blockingGet());
      sync.onComplete();
      context.blockUntilIdle(TIMEOUT);
    }
  }

  @Test
  public void testResolveCustom() {
    try (val kb = new KnowledgeBase()) {
      val context = kb.newContext();
      val resolveCustom = kb.new InvocationNode(kb.node(KnowledgeBase.Bootstrap.resolve))
          .literal(kb.node(KnowledgeBase.Common.name), kb.node("foo"));
      resolveCustom.activate(context);
      context.blockUntilIdle();
      assertEquals(kb.node("foo"), context.node.properties.get(resolveCustom));
    }
  }

  @Test
  public void testResolveBootstrap() {
    try (val kb = new KnowledgeBase()) {
      val context = kb.newContext();
      val resolveCustom = kb.new InvocationNode(kb.node(KnowledgeBase.Bootstrap.resolve))
          .literal(kb.node(KnowledgeBase.Common.name), kb.node("eval"));
      resolveCustom.activate(context);
      context.blockUntilIdle();
      assertEquals(kb.node(KnowledgeBase.Bootstrap.eval), context.node.properties.get(resolveCustom));
    }
  }
}
