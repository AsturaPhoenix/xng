package ai.xng;

import java.util.concurrent.Executors;

import io.reactivex.disposables.Disposable;

public abstract class Scheduler {
  public static Scheduler global = new FlexTimeScheduler(Executors.newSingleThreadExecutor());

  public abstract long now();

  public abstract Disposable postTask(Runnable task);

  public abstract Disposable postTask(Runnable task, long time);

  public abstract void fastForwardUntilIdle();

  public abstract void fastForwardUntil(final long t);

  public void fastForwardFor(final long dt) {
    fastForwardUntil(now() + dt);
  }
}