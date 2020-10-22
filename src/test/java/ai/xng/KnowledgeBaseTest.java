package ai.xng;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import lombok.val;

public class KnowledgeBaseTest {
  @Test
  public void testActionExceptionHandling() {
    try (val kb = new KnowledgeBase()) {
      val exception = new RuntimeException("foo");
      val thrower = kb.actions.new Node(() -> {
        throw exception;
      });
      thrower.activate();
      assertSame(exception, kb.lastException.getData());
    }
  }
}
