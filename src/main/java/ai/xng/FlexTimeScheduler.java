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

import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import lombok.AllArgsConstructor;
import lombok.val;

/**
 * A sequential scheduler based on a combination of real and fake time. Tasks
 * are executed in a non-overlapping manner. While the task queue is empty, the
 * thread is returned to a common pool.
 * <p>
 * Time only advances while the task queue is empty, waiting for a task
 * deadline, or the dispatch loop is not active.
 */
public class FlexTimeScheduler extends Scheduler {
  @AllArgsConstructor
  private class Task implements Comparable<Task>, Disposable {
    final Disposable parent;
    final long deadline, sequenceNumber;
    volatile Runnable run;

    @Override
    public int compareTo(final Task o) {
      return ComparisonChain.start()
          .compare(deadline, o.deadline)
          .compare(sequenceNumber, o.sequenceNumber)
          .result();
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

  private final Executor threadPool;

  // guards the task queue
  private final Lock lock = new ReentrantLock();
  // used to wait for delayed tasks
  private final Condition delayCondition = lock.newCondition();

  private long sequenceNumber = Long.MIN_VALUE;
  private final PriorityQueue<Task> tasks = new PriorityQueue<>();
  private CompletableFuture<Thread> thread;
  private Disposable controller = Disposables.empty();

  private int pauseCount;
  private final Condition pauseCondition = lock.newCondition();

  public enum TimeMode {
    REAL,
    FAKE
  }

  private long time;
  private TimeMode timeMode = TimeMode.REAL;

  private void setTimeMode(final TimeMode timeMode) {
    if (this.timeMode != timeMode) {
      if (timeMode == TimeMode.REAL) {
        time -= System.currentTimeMillis();
      } else {
        time += System.currentTimeMillis();
      }
      this.timeMode = timeMode;
    }
  }

  /**
   * Creates a scheduler that uses {@code threadPool} to run the dispatch loop. As
   * long as tasks are pending, this will hold onto a thread/task.
   */
  public FlexTimeScheduler(final Executor threadPool) {
    this.threadPool = threadPool;
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
    setTimeMode(TimeMode.FAKE);
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
      setTimeMode(TimeMode.REAL);
    }
  }

  private void dispatch() {
    thread.complete(Thread.currentThread());

    setTimeMode(TimeMode.FAKE);

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
    // In handling interruption, we need to be careful of a pathological case where
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
        if (!Thread.currentThread()
            .isInterrupted()) {
          while (!tasks.isEmpty() && pauseCount == 0) {
            final Task head = tasks.peek();

            if (head.isDisposed()) {
              tasks.poll();
              continue;
            }

            final long now = now();
            // Use deadline > now instead of delta > 0 for overflow robustness.
            if (head.deadline > now) {
              final long delta = head.deadline - now;
              // Getting a real/fake-time flip right here is very tricky because time may be
              // queried while we're waiting and a new earlier task might pre-empt the one
              // we're waiting for. Since this is not currently on a critical logical path,
              // take the simple route of flipping to real time until the wait is over, and
              // then flipping back naively.
              setTimeMode(TimeMode.REAL);
              delayCondition.await(delta > 0 ? delta : Long.MAX_VALUE, TimeUnit.MILLISECONDS);
              setTimeMode(TimeMode.FAKE);
              continue;
            }

            final Runnable run = tasks.poll().run;
            if (run != null)
              return run;
          }
        }
      } catch (final InterruptedException e) {
        Thread.currentThread()
            .interrupt();
      }

      if (pauseCount > 0) {
        // Cede the thread and wake the pausers.
        thread = null;
        pauseCondition.signalAll();
      } else if (tasks.isEmpty()) {
        // Indicate that we're ceding the thread while we're holding the lock.
        thread = null;
        setTimeMode(TimeMode.REAL);
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
   * Drains the task queue, blocking until empty. Pauses the dispatch thread while
   * active (and blocks until it cedes). This should not be used while periodic
   * tasks are in the queue.
   */
  @Override
  public void fastForwardUntilIdle() {
    lock.lock();
    try {
      pause();
      setTimeMode(TimeMode.FAKE);
      while (!tasks.isEmpty()) {
        final Task task = tasks.poll();
        final long delta = task.deadline - now();
        if (delta > 0) {
          time += delta;
        }

        if (task.run != null) {
          task.run.run();
        }
      }
      setTimeMode(TimeMode.REAL);
    } finally {
      resume();
      lock.unlock();
    }
  }

  @Override
  public void fastForwardUntil(final long target) {
    lock.lock();
    try {
      pause();
      setTimeMode(TimeMode.FAKE);
      long next;
      while (!tasks.isEmpty() && (next = tasks.peek().deadline) <= target) {
        final long delta = next - now();
        if (delta > 0) {
          time += delta;
        }

        final Task task = tasks.poll();
        if (task.run != null) {
          task.run.run();
        }
      }

      if (time < target) {
        time = target;
      }
      setTimeMode(TimeMode.REAL);
    } finally {
      resume();
      lock.unlock();
    }
  }

  @Override
  public void fastForwardFor(final long dt) {
    fastForwardUntil(now() + dt);
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

  /**
   * Interrupts any ongoing tasks and rejects any new tasks. Does not block; to
   * block, call {@link #pause()} afterwards.
   */
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

  @Override
  public long now() {
    lock.lock();
    try {
      return timeMode == TimeMode.REAL ? System.currentTimeMillis() + time : time;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Disposable postTask(Runnable task) {
    return postTask(task, now());
  }

  /**
   * Schedules the execution of the given task with the given target time. Target
   * times before {@link #now()} may be used to raise priority. This method is
   * safe to be called from multiple threads. Tasks scheduled from the same thread
   * (or otherwise synchronized) with the same delay will be executed in order.
   */
  @Override
  public Disposable postTask(final Runnable run, final long time) {
    return schedule(controller, run, time);
  }
}