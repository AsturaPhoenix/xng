package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import lombok.val;

public class LanguageTest {
  @Test
  public void testHelloWorld() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    try (val kb = new KnowledgeBase()) {
      new LanguageBootstrap(kb);
      val monitor = EmissionMonitor.fromObservable(kb.rxOutput());
      kb.inputValue.setData("print(\"Hello, world!\")");
      scheduler.fastForwardUntilIdle();
      assertThat(monitor.emissions()).containsExactly("Hello, world!");

      scheduler.fastForwardFor(IntegrationProfile.PERSISTENT.period());

      kb.inputValue.setData("print(\"Goodnight, moon!\")");
      scheduler.fastForwardUntilIdle();
      assertThat(monitor.emissions()).containsExactly("Goodnight, moon!");
    }
  }
}
