package ai.xng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import ai.xng.KnowledgeBase.BuiltIn;
import ai.xng.KnowledgeBase.Common;
import io.reactivex.subjects.CompletableSubject;
import lombok.val;

public class KnowledgeBaseTest {
  @Test
  public void testNodeCreation() {
    try (val kb = new KnowledgeBase()) {
      val a = kb.node(), b = kb.node();
      assertNotSame(a, b);
    }
  }

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
      val invocation = kb.new Invocation(kb.node(), kb.node(BuiltIn.print));
      assertEquals(1, invocation.node.properties.size());
      invocation.literal(kb.node(Common.value), kb.node("foo"));
      assertEquals(2, invocation.node.properties.size());
      assertEquals(1, invocation.node.properties.get(kb.node(Common.literal)).properties.size());
    }
  }

  private static void setUpPropGet(final KnowledgeBase kb) {
    final Node roses = kb.node("roses"), color = kb.node("color");
    roses.properties.put(color, kb.node("red"));

    final Node rosesAre = kb.node("roses are");
    kb.new Invocation(rosesAre, kb.node(BuiltIn.getProperty)).literal(kb.node(Common.object), roses)
        .literal(kb.node(Common.name), color);

    rosesAre.then(kb.new Invocation(kb.node("print invocation"), kb.node(BuiltIn.print))
        .transform(kb.node(Common.value), rosesAre).node);
  }

  private static void assertPropGet(final KnowledgeBase kb) {
    val sanity1 = new EmissionMonitor<>(kb.node("roses are").rxActivate()),
        sanity2 = new EmissionMonitor<>(kb.node(BuiltIn.getProperty).rxActivate()),
        sanity3 = new EmissionMonitor<>(kb.node(BuiltIn.print).rxActivate());
    val valueMonitor = new EmissionMonitor<>(kb.rxOutput());

    final Context context = kb.newContext();
    context.fatalOnExceptions();

    kb.node("roses are").activate(context);
    context.blockUntilIdle();
    assertTrue(sanity1.didEmit());
    assertTrue(sanity2.didEmit());
    try {
      assertTrue(sanity3.didEmit());
    } catch (final Throwable t) {
      final Synapse synapse = kb.node("print invocation").getSynapse();
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
        kb.node();
      }

      gc.assertNoGrowth();
    }
  }

  @Test
  public void testSynapseGc() throws Exception {
    try (val kb = new KnowledgeBase()) {
      val posterior = kb.node();

      val gc = new GcFixture(kb);

      for (int i = 0; i < 1000; ++i) {
        kb.node().then(posterior);
      }

      gc.assertNoGrowth();
    }
  }

  @Test
  public void testCycleGc() throws Exception {
    try (val kb = new KnowledgeBase()) {
      val gc = new GcFixture(kb);

      for (int i = 0; i < 1000; ++i) {
        val a = kb.node(), b = kb.node();
        a.then(b).then(a);
      }

      gc.assertNoGrowth();
    }
  }

  @Test
  public void testActivationGc() throws Exception {
    try (val kb = new KnowledgeBase()) {
      val node = kb.node();
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
    try (val kb = new KnowledgeBase()) {
      val posterior = kb.node();

      {
        val prior = kb.node();
        prior.then(posterior);

        val context = kb.newContext();
        prior.activate(context);
        context.blockUntilIdle();
      }

      val gc = new GcFixture(kb);

      for (int i = 0; i < 1000; ++i) {
        val prior = kb.node();
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
      final Node exceptionHandler = kb.node();
      val monitor = new EmissionMonitor<>(exceptionHandler.rxActivate());

      final Node invocation = kb.node();
      kb.new Invocation(invocation, kb.node(BuiltIn.print)).exceptionHandler(exceptionHandler);
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
      kb.new Invocation(kb.node(), kb.node()).node.activate(context);
      context.blockUntilIdle(Duration.ofMillis(500));
    }
  }

  @Test
  public void testParentContextInheritsChildActivity() {
    try (val kb = new KnowledgeBase()) {
      val block = kb.node();
      val sync = CompletableSubject.create();
      block.setOnActivate(c -> sync);
      val returnToParent = kb.new Invocation(kb.node(), kb.node(BuiltIn.setProperty)).literal(kb.node(Common.name),
          kb.node(Common.returnValue)).node;
      returnToParent.then(block);

      val invoke = kb.new Invocation(kb.node(), returnToParent).node;
      val end = kb.node();
      val monitor = new EmissionMonitor<>(end.rxActivate());
      invoke.then(end);

      val context = kb.newContext();
      invoke.activate(context);
      assertTrue(monitor.didEmit());
      assertEquals(Arrays.asList(true), context.rxActive().take(500, TimeUnit.MILLISECONDS).toList().blockingGet());
      sync.onComplete();
      context.blockUntilIdle(Duration.ofMillis(500));
    }
  }
}
