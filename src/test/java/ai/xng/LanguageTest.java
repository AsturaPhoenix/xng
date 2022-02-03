package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import lombok.val;

public class LanguageTest {
  private static final String HELLO_WORLD = "print(\"Hello, world!\")";

  private final TestScheduler scheduler = new TestScheduler();
  {
    Scheduler.global = scheduler;
  }

  private final KnowledgeBase kb = new KnowledgeBase();

  @AfterEach
  public void closeKb() {
    kb.close();
  }

  private final LanguageBootstrap bootstrap = new LanguageBootstrap(kb);

  private void doInput(final String input) {
    kb.inputValue.setData(input);
  }

  @Test
  public void testCallEval() {
    val monitor = new EmissionMonitor<Long>();
    bootstrap.eval.entrypoint.then(TestUtil.testNode(kb.actions, monitor));

    doInput(HELLO_WORLD);
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testCallParse() {
    val monitor = new EmissionMonitor<Long>();
    bootstrap.parse.entrypoint.then(TestUtil.testNode(kb.actions, monitor));

    doInput(HELLO_WORLD);
    scheduler.fastForwardUntilIdle();
    assertTrue(monitor.didEmit());
  }

  @Test
  public void testParseArg() {
    bootstrap.parse.entrypoint.then(kb.suppressPosteriors(kb.execution));
    doInput(HELLO_WORLD);
    scheduler.fastForwardUntilIdle();

    val monitor = EmissionMonitor.fromObservable(kb.data.rxActivations());
    bootstrap.control.stackFrame.address.activate();
    bootstrap.control.arg1.trigger();
    scheduler.fastForwardUntilIdle();
    assertThat(monitor.emissions()).extracting(DataCluster.Node::getData).containsExactly(HELLO_WORLD);
  }

  @Test
  public void testHelloGoodbye() {
    val monitor = EmissionMonitor.fromObservable(kb.rxOutput());
    doInput(HELLO_WORLD);
    scheduler.fastForwardUntilIdle();
    assertThat(monitor.emissions()).containsExactly("Hello, world!");

    scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());

    doInput("print(\"Goodnight, moon!\")");
    scheduler.fastForwardUntilIdle();
    assertThat(monitor.emissions()).containsExactly("Goodnight, moon!");
  }
}
