package ai.xng;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;

public abstract class Scheduler {
  public static Scheduler global = new RealTimeScheduler(Executors.newSingleThreadExecutor());

  public abstract long now();

  public abstract Disposable postTask(Runnable task);

  public abstract Disposable postTask(Runnable task, long time);

  @Getter(lazy = true)
  private final io.reactivex.Scheduler rx = new RxScheduler(this);

  @RequiredArgsConstructor
  private static class RxScheduler extends io.reactivex.Scheduler {
    final Scheduler scheduler;

    @Override
    public long now(final TimeUnit unit) {
      return unit.convert(scheduler.now(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Disposable scheduleDirect(final Runnable run) {
      return scheduler.postTask(run);
    }

    @Override
    public Disposable scheduleDirect(final Runnable run, final long delay, final TimeUnit unit) {
      return scheduler.postTask(run, unit.toMillis(delay));
    }

    @Override
    public Worker createWorker() {
      return new Worker() {
        CompositeDisposable workerDisposable = new CompositeDisposable();

        @Override
        public long now(TimeUnit unit) {
          return RxScheduler.this.now(unit);
        }

        @Override
        public void dispose() {
          workerDisposable.dispose();
        }

        @Override
        public boolean isDisposed() {
          return workerDisposable.isDisposed();
        }

        @Override
        public Disposable schedule(final Runnable run) {
          val taskDisposable = scheduler.postTask(run);
          workerDisposable.add(taskDisposable);
          return taskDisposable;
        }

        @Override
        public Disposable schedule(final Runnable run, final long delay, final TimeUnit unit) {
          val taskDisposable = scheduler.postTask(run, scheduler.now() + unit.toMillis(delay));
          workerDisposable.add(taskDisposable);
          return taskDisposable;
        }
      };
    }
  }
}