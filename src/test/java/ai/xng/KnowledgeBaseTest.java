package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.assertj.core.util.Throwables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ai.xng.KnowledgeBase.Bootstrap;
import ai.xng.KnowledgeBase.BuiltIn;
import ai.xng.KnowledgeBase.Common;
import ai.xng.Node.Activation;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.subjects.CompletableSubject;
import lombok.val;

public class KnowledgeBaseTest {
  private static final Duration TIMEOUT = Duration.ofMillis(500);

  @Test
  public void testEmptySerialization() throws Exception {
    assertNotNull(TestUtil.serialize(new KnowledgeBase()));
  }

  @Test
  public void testEavBeforeEntrypoint() {
    try (val kb = new KnowledgeBase()) {
      val entrypoint = new SynapticNode(), param = new SynapticNode(), eav = kb.eavNode(true, true, param),
          race = new SynapticNode();
      race.getSynapse().setCoefficient(eav, -1);
      race.getSynapse().setCoefficient(entrypoint, 1);

      val invocation = kb.new InvocationNode(entrypoint).literal(param, param);
      val monitor = new EmissionMonitor<>(race.rxActivate());
      for (int i = 0; i < 1000; ++i) {
        val context = kb.newContext();
        invocation.activate(context);
        context.blockUntilIdle();
        assertFalse(monitor.didEmit());
      }
    }
  }

  @Test
  public void testNestedEavBeforeEntrypoint() {
    try (val kb = new KnowledgeBase()) {
      val entrypoint = new SynapticNode(), parent = new SynapticNode(), child = new SynapticNode(),
          eav = kb.eavNode(true, true, parent, child), race = new SynapticNode();
      parent.properties.put(child, child);
      race.getSynapse().setCoefficient(eav, -1);
      race.getSynapse().setCoefficient(entrypoint, 1);

      val invocation = kb.new InvocationNode(entrypoint).literal(parent, parent);
      val monitor = new EmissionMonitor<>(race.rxActivate());
      for (int i = 0; i < 1000; ++i) {
        val context = kb.newContext();
        invocation.activate(context);
        context.blockUntilIdle();
        assertFalse(monitor.didEmit());
      }
    }
  }

  private static void testPrint(final KnowledgeBase kb) throws Exception {
    val monitor = new EmissionMonitor<>(kb.rxOutput());

    val context = kb.newContext();
    context.node.properties.put(kb.node(Common.value), kb.node("foo"));
    kb.node(BuiltIn.print).activate(context);
    context.blockUntilIdle();
    assertThat(monitor.emissions()).containsExactly("foo");
  }

  @Test
  @Timeout(1)
  public void testPrint() throws Exception {
    testPrint(new KnowledgeBase());
  }

  @Test
  @Timeout(1)
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

  private static void assertPropGet(final KnowledgeBase kb) throws TimeoutException {
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
      final Synapse synapse = ((SynapticNode) print).getSynapse();
      System.err.printf("Invocation synapse:\n%s\n\nRecent evaluations:\n%s\n\n", synapse,
          synapse.getRecentEvaluations(context, System.currentTimeMillis() - 500));
      throw t;
    }
    assertThat(valueMonitor.emissions()).containsExactly("red");
  }

  @Test
  public void testPrintProp() throws Exception {
    try (val kb = new KnowledgeBase()) {
      setUpPropGet(kb);
      assertPropGet(kb);
    }
  }

  @Test
  public void testPrintReliability() throws Exception {
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

      // TODO(rosswang): If we ever elect to capture the node at finer than invocation
      // granularity, the deepest frame in this case may be BuiltIn.print, followed by
      // the invocation.
      assertThat(monitor.emissions())
          .extracting(activation -> activation.context.node.properties.get(kb.node(Common.exception)))
          .extracting(exception -> exception.properties.get(kb.node(Common.source))).containsExactly(invocation);
    }
  }

  /**
   * Ensures that the invocation of a trivial custom subroutine completes.
   */
  @Test
  public void testCustomInvocationCompletes() throws Exception {
    try (val kb = new KnowledgeBase()) {
      val context = kb.newContext();
      kb.new InvocationNode(new Node()).activate(context);
      context.blockUntilIdle(TIMEOUT);
    }
  }

  @Test
  @Timeout(1)
  public void testParentContextInheritsChildActivity() throws Exception {
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
      final Single<Activation> endMonitor = end.rxActivate().firstOrError().cache();
      endMonitor.subscribe();
      invoke.then(end);

      val context = kb.newContext();
      invoke.activate(context);
      endMonitor.blockingGet();
      assertThat(context.rxActive().take(500, TimeUnit.MILLISECONDS).toList().blockingGet()).containsExactly(true);
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

  private static class TestRepl {
    final Repl repl;

    TestRepl(final KnowledgeBase kb) {
      repl = new Repl(kb);
    }

    String eval(final String input) throws InterruptedException, ExecutionException {
      val sb = new StringBuilder();
      val async = new AsyncJoiner();
      val out = repl.rxOutput().subscribe(sb::append);
      val err = repl.rxError().subscribe(async.future::completeExceptionally);

      try {
        async.add(repl.sendInput(input));
        async.add(repl.rootContext.rxActive().filter(a -> !a).firstElement().ignoreElement());
        async.arrive();
        async.future.get();
      } finally {
        out.dispose();
        err.dispose();
      }
      return sb.toString();
    }
  }

  @Test
  public void testEval() throws Exception {
    try (val kb = new KnowledgeBase()) {
      new LanguageBootstrap(kb).bootstrap();
      val repl = new TestRepl(kb);

      assertEquals("BuiltIn.print", repl.eval("print(value: 'print)"));
      assertEquals("null", repl.eval("print(value: node())"));
    }
  }

  @Test
  public void testNamedArgsConsistency() throws Exception {
    try (val kb = new KnowledgeBase()) {
      new LanguageBootstrap(kb).bootstrap();
      val repl = new TestRepl(kb);

      for (int i = 0; i < 200; ++i) {
        assertEquals("BuiltIn.node", repl.eval("print(value: 'node)"), String.format("Failed at iteration %s.", i));
      }
    }
  }

  @Test
  public void testEvalReinforcementStability() throws Exception {
    try (val kb = new KnowledgeBase()) {
      new LanguageBootstrap(kb).bootstrap();

      final Node invocation = kb.new InvocationNode(kb.node(Bootstrap.eval)).literal(kb.node(Common.value),
          kb.node("print(value: 'node)"));
      val monitor = new EmissionMonitor<>(kb.rxOutput());

      for (int i = 0; i < 10; ++i) {
        val context = kb.newContext();
        invocation.activate(context);
        context.blockUntilIdle();
        assertThat(monitor.emissions()).as("iteration %s", i).containsExactly("BuiltIn.node");
        KnowledgeBase.reinforceRecursively(context, 1).join();
      }
    }
  }

  /**
   * Simple precondition tweaks should be correctable through reinforcement.
   * Starting with a naive (buggy) Fibonacci sequence generator, train it to
   * correctness.
   */
  @Test
  public void testFibonacciFixByReinforcement() {
    try (val kb = new KnowledgeBase()) {
      val count = new Node(), current = new Node(), previous = new Node(), out = new Node();
      val iteration = new Node();

      val countSign = kb.new InvocationNode(kb.node(BuiltIn.method))
          .literal(kb.node(Common.javaClass), kb.node(Integer.class)).literal(kb.node(Common.name), kb.node("signum"))
          .literal(kb.param(1), kb.node(int.class)).transform(kb.arg(1), count);
      iteration.then(countSign);

      val doFib = kb.eavNode(true, false, countSign, kb.node(1));

      // TODO: stronger function pointer story.
      val publish = kb.new InvocationNode(kb.node(BuiltIn.method))
          .literal(kb.node(Common.javaClass), kb.node(List.class)).transform(kb.node(Common.object), out)
          .literal(kb.node(Common.name), kb.node("add")).literal(kb.param(1), kb.node(Object.class))
          .transform(kb.arg(1), current);
      doFib.then(publish);

      val add = kb.new InvocationNode(kb.node(BuiltIn.method)).literal(kb.node(Common.javaClass), kb.node(Math.class))
          .literal(kb.node(Common.name), kb.node("addExact")).literal(kb.param(1), kb.node(int.class))
          .literal(kb.param(2), kb.node(int.class)).transform(kb.arg(1), previous).transform(kb.arg(2), current);
      doFib.then(add);

      val nextCount = kb.new InvocationNode(kb.node(BuiltIn.method))
          .literal(kb.node(Common.javaClass), kb.node(Math.class))
          .literal(kb.node(Common.name), kb.node("decrementExact")).literal(kb.param(1), kb.node(int.class))
          .transform(kb.arg(1), count);
      doFib.then(nextCount);

      val recurse = kb.new InvocationNode(iteration).inherit(out).transform(current, add).transform(previous, current)
          .transform(count, nextCount);
      // should also predicate on publish and add:
      // recurse.conjunction(publish, add, nextCount);
      nextCount.then(recurse);

      val fib = new Node();
      val createOut = kb.new InvocationNode(kb.node(Bootstrap.newInstance)).literal(kb.node(Common.javaClass),
          kb.node(ArrayList.class));
      fib.then(createOut);
      val iterate = kb.new InvocationNode(iteration).inherit(count).transform(out, createOut)
          .literal(current, kb.node(1)).literal(previous, kb.node(0));
      createOut.then(iterate);
      val returnOut = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .literal(kb.node(Common.name), kb.node(Common.returnValue)).transform(kb.node(Common.value), createOut);
      iterate.then(returnOut);

      val fixture = new LearningFixture(100, 1000);
      do {
        val context = kb.newContext();
        context.fatalOnExceptions();
        context.node.properties.put(count, kb.node(10));
        fib.activate(context);
        try {
          context.blockUntilIdle();
        } catch (final RuntimeException e) {
          fixture.fail(Throwables.getStackTrace(e));
          continue;
        }

        final Node result = context.node.properties.get(kb.node(Common.returnValue));

        if (result != null && result.getValue() instanceof List<?>
            && result.getValue().equals(Arrays.asList(1, 1, 2, 3, 5, 8, 13, 21, 34, 55))) {
          fixture.pass();
          KnowledgeBase.reinforceRecursively(context, 1).join();
        } else {
          fixture.fail(String.format("result = %s", result));
        }
      } while (fixture.shouldContinue());
    }
  }
}
