package ai.xng;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Completable;

/**
 * Cheap void {@link CompletableFuture#allOf(CompletableFuture...)}.
 */
public class AsyncJoiner {
  private final AtomicInteger count = new AtomicInteger(1);
  public final CompletableFuture<Void> future = new CompletableFuture<>();

  public void add(final CompletionStage<?> task) {
    register();
    task.thenRun(this::arrive).exceptionally(t -> {
      future.completeExceptionally(t);
      return null;
    });
  }

  public void add(final Completable task) {
    register();
    task.subscribe(this::arrive, future::completeExceptionally);
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
