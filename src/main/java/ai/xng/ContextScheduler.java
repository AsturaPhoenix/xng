package ai.xng;

import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.ComparisonChain;

import io.reactivex.Scheduler;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.experimental.Accessors;

/**
 * A sequential scheduler for contexts. Tasks are executed in a non-overlapping
 * manner. While the task queue is empty, the thread is returned to a common
 * pool.
 */
public class ContextScheduler extends Scheduler implements Executor {
  private static final TimeUnit RESOLUTION = TimeUnit.MILLISECONDS;

  @AllArgsConstructor
  private class Task implements Comparable<Task>, Disposable {
    final Disposable parent;
    final long deadline, sequenceNumber;
    volatile Runnable run;

    @Override
    public int compareTo(final Task o) {
      return ComparisonChain.start().compare(deadline, o.deadline).compare(sequenceNumber, o.sequenceNumber).result();
    }

    @Override
    public void dispose() {
      run = null;
    }

    @Override
    public boolean isDisposed() {
      return run == null || parent.isDisposed();
    }
  }

  @RequiredArgsConstructor
  private static abstract class LazyShutdownWorker extends Scheduler.Worker {
    final Disposable parent;
    volatile boolean isDisposed;

    @Override
    public void dispose() {
      isDisposed = true;
    }

    @Override
    public boolean isDisposed() {
      return isDisposed || parent.isDisposed();
    }
  }

  private class Worker extends LazyShutdownWorker {
    Worker(final Disposable parent) {
      super(parent);
    }

    @Override
    public Disposable schedule(final Runnable run, final long delay, final TimeUnit unit) {
      return ContextScheduler.this.schedule(this, run, deadline(delay, unit));
    }
  }

  private final Executor threadPool;

  // guards the task queue
  private final Lock lock = new ReentrantLock();
  // used to wait for delayed tasks
  private final Condition delayCondition = lock.newCondition();

  private long sequenceNumber = Long.MIN_VALUE;
  private final PriorityQueue<Task> tasks = new PriorityQueue<>();
  private CompletableFuture<Thread> thread;
  private Disposable controller = Disposables.disposed();

  private int pauseCount;
  private final Condition pauseCondition = lock.newCondition();

  /**
   * Creates a scheduler that uses {@code threadPool} to run the dispatch loop. As
   * long as tasks are pending, this will hold onto a thread/task.
   */
  public ContextScheduler(final Executor threadPool) {
    this.threadPool = threadPool;
  }

  private long deadline(final long delay, final TimeUnit unit) {
    return now(RESOLUTION) + RESOLUTION.convert(delay, unit);
  }

  private Disposable schedule(final Disposable controller, final Runnable run, final long deadline) {
    lock.lock();
    try {
      if (controller.isDisposed()) {
        return Disposables.disposed();
      }

      final Task task = new Task(controller, deadline, sequenceNumber++, run);
      tasks.add(task);
      if (thread == null) {
        if (pauseCount == 0) {
          // Handle RejectedExecutionException in stride. This behavior is consistent with
          // Schedulers.from(Executor). Alternate strategies include rethrowing, with or
          // without enqueuing.
          startDispatch();
        }
      } else {
        if (tasks.peek() == task) {
          delayCondition.signal();
        }
      }
      return task;
    } finally {
      lock.unlock();
    }
  }

  private void startDispatch() {
    thread = new CompletableFuture<>();
    try {
      threadPool.execute(this::dispatch);
    } catch (final RejectedExecutionException e) {
      thread = null;
      // Cancel all pending tasks, but leave the door open for a soft restart if the
      // executor allows it.
      controller.dispose();
      controller = Disposables.empty();
      tasks.clear();
    }
  }

  private void dispatch() {
    thread.complete(Thread.currentThread());

    Runnable task;
    // nextTask contains the synchronized mechanics of the dispatch loop.
    while ((task = nextTask()) != null) {
      task.run();
    }
  }

  /**
   * Waits for the next valid task to be up for execution.
   */
  private Runnable nextTask() {
    // In handling itnerruption, we need to be careful of a pathological case where
    // a task executing during shutdown takes until a restart to complete. At that
    // point, we need to ensure that new tasks posted before we handle the
    // interruption are executed as expected.
    //
    // Several strategies for doing this:
    // * Use independent state (thread and task queue) for each restart.
    // -- This was the original strategy, but it would require additional handling
    // -- to ensure that the task still executing during shutdown does not keep
    // -- executing concurrently with new tasks posted since the restart.
    // * Block shutdown until tasks have been stopped.
    // -- This is a sound strategy but may introduce undesired blocking and is
    // -- unlike other Scheduler implementations, which do not wait for termination.
    // * Use robust state to determine dispatch loop lifecycle and carefully manage
    // -- interrupt state.
    // -- This is mostly sound but may interfere with shutdown of the backing thread
    // -- pool as we no longer necessarily honor interruption of the dispatch thread
    // -- outside of our own shutdown implementation that clears the task queue.
    // * Atomically uninterrupt the thread on restart if still alive.
    // -- Unfortunately uninterrupting the thread would require a dance and it'd be
    // -- impossible to truly distinguish between our own shutdown interrupt and
    // -- backing threadpool shutdown.
    // * Interrupt, but request a new dispatch thread if the task queue is not
    // -- empty.
    lock.lock();
    try {
      try {
        // Just poll this once per work loop. (During wait, we'll have
        // InterruptedException instead.) Don't bother polling it while we consume
        // disposed tasks. Also make sure not to clear interruption.
        if (!Thread.currentThread().isInterrupted()) {
          while (!tasks.isEmpty() && pauseCount == 0) {
            final Task head = tasks.peek();

            if (head.isDisposed()) {
              tasks.poll();
              continue;
            }

            final long now = now(RESOLUTION);
            // Use deadline > now instead of delta > 0 for overflow robustness.
            if (head.deadline > now) {
              delayCondition.await(head.deadline - now, RESOLUTION);
              continue;
            }

            final Runnable run = tasks.poll().run;
            if (run != null)
              return run;
          }
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      if (pauseCount > 0) {
        // Cede the thread and wake the pausers.
        thread = null;
        pauseCondition.signalAll();
      } else if (tasks.isEmpty()) {
        // Indicate that we're ceding the thread while we're holding the lock.
        thread = null;
      } else {
        // Try to restart. If we're actually shutting down, we'll have cleared the
        // queue. Otherwise, we want to at least try to restart, to cover the case where
        // we were restarted while a task was executing. If the backing thread pool is
        // shutting down, we won't be able to.
        startDispatch();
      }
      return null;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Instructs the dispatch thread to suspend, and blocks until it does.
   */
  public void pause() {
    lock.lock();
    try {
      ++pauseCount;
      if (thread != null && !isOnThread()) {
        // Interrupt any ongoing delay waits and wait for the thread to cede.
        delayCondition.signal();
        pauseCondition.awaitUninterruptibly();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Cancels a {@link #pause()}. Each {@code pause} call must have a
   * {@code resume} call for dispatch to continue.
   */
  public void resume() {
    lock.lock();
    try {
      if (pauseCount <= 0)
        throw new IllegalStateException("Scheduler is not paused.");
      --pauseCount;

      if (pauseCount == 0 && !tasks.isEmpty()) {
        startDispatch();
      }
    } finally {
      lock.unlock();
    }
  }

  private Thread getThreadNow() {
    // local variable for unsynchronized use in isOnThread.
    val thread = this.thread;
    return thread == null ? null : thread.getNow(null);
  }

  /**
   * Returns whether we're on the dispatch thread.
   * 
   * In the case of a same-thread backing executor, this will still return false
   * while a task is not being executed since there's no guarantee outside of
   * scheduling the task that another thread won't attempt to do the same.
   * 
   * Likewise for a single-thread executor, this will still return false while a
   * task is not being executed even if called from that executor's thread as this
   * scheduler is not aware of the executor's threading details.
   */
  public boolean isOnThread() {
    // Although this is not synchronized, this condition is guaranteed to be valid.
    return getThreadNow() == Thread.currentThread();
  }

  @Override
  public Scheduler.Worker createWorker() {
    return new Worker(controller);
  }

  @Override
  public void start() {
    lock.lock();
    try {
      if (controller.isDisposed()) {
        controller = Disposables.empty();
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void shutdown() {
    lock.lock();
    try {
      // This is mostly idempotent so we wouldn't need to predicate on isDisposed,
      // except if a task implementation is refusing to interrupt, calling this
      // repeatedly would repeatedly issue interrupt requests. While that's probably
      // not a terrible thing to do, it isn't idempotent.
      if (!controller.isDisposed()) {
        tasks.clear();
        sequenceNumber = Long.MIN_VALUE;

        // Since we're synchronized here, if the thread has not yet reached a sync
        // point, don't bother interrupting it since we cleared our task queue anyway.
        //
        // An alternative would be to use thenAccept, but its interaction with start()
        // gets complicated.
        val thread = getThreadNow();
        if (thread != null) {
          try {
            thread.interrupt();
          } catch (final SecurityException ignore) {
            // Best-effort shutdown should not throw.
          }
        }
        controller.dispose();
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Schedules the execution of the given task with the given delay amount.
   * Negative delays may be used to raise priority. This method is safe to be
   * called from multiple threads. Tasks scheduled from the same thread (or
   * otherwise synchronized) with the same delay will be executed in order.
   */
  @Override
  public Disposable scheduleDirect(final Runnable run, final long delay, final TimeUnit unit) {
    return schedule(controller, run, deadline(delay, unit));
  }

  public void ensureOnThread(final Runnable run) {
    if (isOnThread()) {
      run.run();
    } else {
      scheduleDirect(run);
    }
  }

  /**
   * {@link Executor} equivalent of {@link #scheduleDirect(Runnable)}.
   * 
   * @throws RejectedExecutionException if the scheduler is shut down.
   */
  @Override
  public void execute(final Runnable command) {
    final Disposable task = scheduleDirect(command);
    if (task.isDisposed())
      throw new RejectedExecutionException("ContextScheduler is not started.");
  }

  /**
   * A view of a {@link ContextScheduler} that prefers to run actions immediately
   * if scheduled from the dispatch thread without delay.
   */
  private class PreferImmediateScheduler extends Scheduler {
    class Worker extends LazyShutdownWorker {
      Worker(final Disposable parent) {
        super(parent);
      }

      @Override
      public Disposable schedule(final Runnable run, final long delay, final TimeUnit unit) {
        return PreferImmediateScheduler.this.schedule(this, run, deadline(delay, unit));
      }
    }

    @Override
    public Worker createWorker() {
      return new Worker(controller);
    }

    Disposable schedule(final Disposable controller, final Runnable run, final long deadline) {
      if (isOnThread() && deadline <= now(RESOLUTION)) {
        run.run();
        return Disposables.empty();
      } else {
        return ContextScheduler.this.schedule(controller, run, deadline);
      }
    }

    /**
     * Schedules the execution of the given task with the given delay amount. If
     * called from the dispatch thread with non-positive delay, the action executes
     * immediately. Otherwise, negative delays may be used to raise priority. This
     * method is safe to be called from multiple threads. Tasks scheduled from the
     * same thread (or otherwise synchronized) with the same delay will be executed
     * in order.
     */
    @Override
    public Disposable scheduleDirect(final Runnable run, final long delay, final TimeUnit unit) {
      return schedule(controller, run, deadline(delay, unit));
    }

    @Override
    public void start() {
      ContextScheduler.this.start();
    }

    @Override
    public void shutdown() {
      ContextScheduler.this.shutdown();
    }
  }

  @Getter(lazy = true)
  @Accessors(fluent = true)
  private final Scheduler preferImmediate = new PreferImmediateScheduler();
}