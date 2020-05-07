package ai.xng;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cheap void {@link CompletableFuture#allOf(CompletableFuture...)}.
 */
public class AsyncJoiner {
  private final AtomicInteger count = new AtomicInteger(1);
  public final CompletableFuture<Void> future = new CompletableFuture<>();

  public void add(final CompletionStage<?> task) {
    register();
    task.thenRun(this::arrive);
  }

  public void register() {
    count.incrementAndGet();
  }

  public void arrive() {
    if (count.decrementAndGet() == 0) {
      future.complete(null);
    }
  }
}
