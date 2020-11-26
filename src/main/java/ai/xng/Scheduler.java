package ai.xng;

import java.util.concurrent.Executors;

import io.reactivex.disposables.Disposable;

public abstract class Scheduler {
  public static Scheduler global = new RealTimeScheduler(Executors.newSingleThreadExecutor());

  public abstract long now();

  public abstract Disposable postTask(Runnable task);

  public abstract Disposable postTask(Runnable task, long time);
}