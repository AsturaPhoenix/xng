package ai.xng;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import lombok.val;

public class LanguageTest {
  @Test
  public void testStringLiteral() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    try (val kb = new KnowledgeBase()) {
      val language = new LanguageBootstrap(kb);
      kb.inputValue.setData("\"Hello, world!\"");
      scheduler.fastForwardUntilIdle();
      assertEquals("Hello, world!", language.literal.getData());
    }
  }

  public void testSequenceCapture() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    try (val kb = new KnowledgeBase()) {
      val language = new LanguageBootstrap(kb);
      kb.inputValue.setData("\"Hello, world!\"");
      scheduler.fastForwardUntilIdle();
      assertEquals("Hello, world!", language.literal.getData());
    }
  }
}
