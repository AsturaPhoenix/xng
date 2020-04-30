package ai.xng;

import static ai.xng.TestUtil.unchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.Range;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Runnables;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import lombok.val;

@Timeout(1)
public class ContextSchedulerTest {
  private static final Executor threadPool = Executors.newFixedThreadPool(4);
  // TODO: use fake time in most tests
  private static final long DELAY = 100;

  @Test
  public void testUnstartedCreateWorker() {
    val worker = new ContextScheduler(threadPool).createWorker();
    assertTrue(worker.isDisposed());
    assertTrue(worker.schedule(Assertions::fail).isDisposed());
  }

  @Test
  public void testStartAfterCreateWorker() {
    val scheduler = new ContextScheduler(threadPool);
    val worker = scheduler.createWorker();
    scheduler.start();

    assertTrue(worker.isDisposed());
    assertTrue(worker.schedule(Assertions::fail).isDisposed());
  }

  @Test
  public void testUnstartedIsNotOnThread() {
    assertFalse(new ContextScheduler(threadPool).isOnThread());
  }

  @Test
  public void testUnstartedDoesNotEnsureOnThread() throws Exception {
    val sync = new CountDownLatch(1);
    new ContextScheduler(threadPool).ensureOnThread(sync::countDown);
    assertFalse(sync.await(DELAY, TimeUnit.MILLISECONDS));
  }

  @Test
  public void testIsOnThread() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val isOnThread = new CompletableFuture<Boolean>();
    scheduler.scheduleDirect(() -> isOnThread.complete(scheduler.isOnThread()));
    assertTrue(isOnThread.get());
    assertFalse(scheduler.isOnThread());
  }

  @Test
  public void testDoesNotConsumeThreadBeforeTask() throws Exception {
    val singleThread = Executors.newSingleThreadExecutor();
    val scheduler = new ContextScheduler(singleThread);
    scheduler.start();

    val sync = new CountDownLatch(1);
    singleThread.execute(sync::countDown);
    assertTrue(sync.await(DELAY, TimeUnit.MILLISECONDS));
  }

  @Test
  public void testCedesThreadWhileInactive() throws Exception {
    val singleThread = Executors.newSingleThreadExecutor();
    val scheduler = new ContextScheduler(singleThread);
    scheduler.start();

    val sync = new Phaser(2);
    scheduler.scheduleDirect(sync::arrive);
    sync.awaitAdvanceInterruptibly(sync.arrive(), DELAY, TimeUnit.MILLISECONDS);

    singleThread.execute(sync::arrive);
    sync.awaitAdvanceInterruptibly(sync.arrive(), DELAY, TimeUnit.MILLISECONDS);
  }

  @Test
  public void testRejectionRecoveryOnDispatchStart() throws Exception {
    // single-threaded executor with no queue capacity
    val singleThread = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
    val scheduler = new ContextScheduler(singleThread);
    scheduler.start();

    // Starve the thread pool before the scheduler requests a thread.
    val starve = new CountDownLatch(1);
    singleThread.execute(TestUtil.unchecked(starve::await));
    assertTrue(scheduler.scheduleDirect(Assertions::fail).isDisposed());

    // Now make sure we gracefully recover if the executor allows it.
    starve.countDown();
    val sync = new CountDownLatch(1);
    assertTimeoutPreemptively(Duration.ofMillis(DELAY), () -> {
      // This needs to be a spin lock because we don't have a way to synchronize with
      // the thread pool.
      while (!Thread.interrupted() && scheduler.scheduleDirect(sync::countDown).isDisposed())
        Thread.yield();
    });
    assertTrue(sync.await(DELAY, TimeUnit.MILLISECONDS));
  }

  /**
   * Verifies that an executing task blocks further tasks.
   */
  @Test
  public void testBlocking() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val blocking = new CountDownLatch(1);
    scheduler.scheduleDirect(unchecked(blocking::await));
    val waiting = new CountDownLatch(1);
    scheduler.scheduleDirect(waiting::countDown);

    assertFalse(waiting.await(DELAY, TimeUnit.MILLISECONDS));
    blocking.countDown();
    assertTrue(waiting.await(DELAY, TimeUnit.MILLISECONDS));
  }

  /**
   * This test ensures that {@link ContextScheduler#ensureOnThread(Runnable)} gets
   * off the main thread. However, it's not entirely a foregone conclusion that
   * this is the best behavior. It would also be correct to execute immediately if
   * the scheduler is otherwise idle. However, that would always require a lock to
   * check idleness, whereas pessimistically assuming activity lets us avoid the
   * lock entirely if we're already on the worker thread.
   */
  @Test
  public void testOffMainThread() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val schedulerThread = new CompletableFuture<>();
    scheduler.ensureOnThread(() -> schedulerThread.complete(Thread.currentThread()));
    assertNotEquals(Thread.currentThread(), schedulerThread.get());
  }

  @Test
  public void testImmediateOnCurrentThread() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val immediate = new CompletableFuture<Boolean>();
    scheduler.scheduleDirect(() -> {
      scheduler.ensureOnThread(() -> immediate.complete(true));
      immediate.complete(false);
    });
    assertTrue(immediate.get());
  }

  @Test
  public void testDeferredOnCurrentThread() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val immediate = new CompletableFuture<Boolean>();
    scheduler.scheduleDirect(() -> {
      scheduler.scheduleDirect(() -> immediate.complete(true));
      immediate.complete(false);
    });
    assertFalse(immediate.get());
  }

  @Test
  public void testFifo() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val reference = ContiguousSet.create(Range.closedOpen(0, 1000), DiscreteDomain.integers());
    for (int i = 0; i < 10; ++i) {
      val list = new ArrayList<Integer>();
      for (final int j : reference) {
        scheduler.scheduleDirect(() -> list.add(j));
      }

      val sync = new CountDownLatch(1);
      scheduler.scheduleDirect(sync::countDown);
      sync.await();

      assertThat(list).containsExactlyElementsOf(reference);
    }
  }

  @Test
  public void testRealTimeDelay() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val sync = new CountDownLatch(1);
    final long start = System.currentTimeMillis();
    scheduler.scheduleDirect(sync::countDown, DELAY, TimeUnit.MILLISECONDS);
    sync.await();
    assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(DELAY);
  }

  @Test
  public void testPreEmptDelayed() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val longDelay = new CountDownLatch(1);
    final long start = System.currentTimeMillis();
    scheduler.scheduleDirect(longDelay::countDown, 2 * DELAY, TimeUnit.MILLISECONDS);

    val shortDelay = new CountDownLatch(1);
    scheduler.scheduleDirect(shortDelay::countDown, DELAY, TimeUnit.MILLISECONDS);

    shortDelay.await();
    assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(DELAY);
    longDelay.await();
    assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(2 * DELAY);
  }

  /**
   * Covers a pathalogical case where a task refuses to be interrupted during
   * shutdown. In this case, we need to block so that we can't end up in an
   * invalid case where we restart and think we're single-threaded while the
   * zombie is still executing.
   */
  @Test
  public void testUninterruptibleShutdown() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val sync = new Phaser(2);
    scheduler.scheduleDirect(() -> {
      while (true) {
        final int phase = sync.arrive();

        // Await, refusing to honor interrupts but honoring(-ish) timeout.
        while (true) {
          try {
            sync.awaitAdvanceInterruptibly(phase, DELAY, TimeUnit.MILLISECONDS);
            break;
          } catch (final InterruptedException ignore) {
          } catch (final TimeoutException e) {
            return;
          }
        }
      }
    });

    sync.arriveAndAwaitAdvance();
    // By now the bad thread is alive.

    // For things to work correctly, some mechanism must exist that blocks the new
    // task after restart until after the zombie task has stopped. This block could
    // happen during shutdown, start, or schedule.
    scheduler.shutdown();
    scheduler.start();

    val badThreadIsDead = new CompletableFuture<Boolean>();
    scheduler.scheduleDirect(() -> {
      // By the time this thread is alive, the bad thread must have terminated. Reset
      // the phaser by dipping its parties to 1.
      sync.arriveAndDeregister();
      sync.register();

      try {
        sync.awaitAdvanceInterruptibly(sync.arrive(), DELAY, TimeUnit.MILLISECONDS);
        badThreadIsDead.complete(false);
      } catch (final TimeoutException e) {
        badThreadIsDead.complete(true);
      } catch (final Exception e) {
        badThreadIsDead.completeExceptionally(e);
      }
    });

    assertTrue(badThreadIsDead.get());
  }

  /**
   * Exercises a pathological case where a task executing during shutdown takes
   * until a restart to complete. A bug could exist that exits the dispatch loop
   * at that point even if new tasks since the restart have already been posted.
   * 
   * Several strategies can ensure this does not happen:
   * <ul>
   * <li>Use independent state (thread and task queue) for each restart.
   * <li>Block shutdown until tasks have been stopped.
   * <li>Use robust state to determine dispatch loop lifecycle and carefully
   * manage interrupt state.
   * </ul>
   */
  @Test
  public void testNewTaskAfterLingeringRestart() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val sync = new Phaser(2);
    scheduler.scheduleDirect(() -> {
      sync.arriveAndAwaitAdvance();
      sync.arriveAndAwaitAdvance();
    });

    sync.arriveAndAwaitAdvance();
    scheduler.shutdown();
    scheduler.start();

    val interrupted = new CompletableFuture<Boolean>();
    scheduler.scheduleDirect(() -> interrupted.complete(Thread.interrupted()));

    sync.arrive();
    assertFalse(interrupted.get(), "Unexpected interrupted status on restarted task thread.");
  }

  @Test
  public void testThreadPoolShutdownPendingDelayed() throws Exception {
    val executor = Executors.newSingleThreadExecutor();
    val scheduler = new ContextScheduler(executor);
    scheduler.start();

    val sync = new CountDownLatch(1);
    scheduler.scheduleDirect(sync::countDown);
    scheduler.scheduleDirect(Runnables.doNothing(), 1, TimeUnit.DAYS);
    sync.await();

    executor.shutdownNow();
    assertTrue(executor.awaitTermination(DELAY, TimeUnit.MILLISECONDS),
        "Scheduler did not honor executor interruption.");
  }

  @Test
  public void testThreadPoolShutdownUnendingTasks() throws Exception {
    val executor = Executors.newSingleThreadExecutor();
    val scheduler = new ContextScheduler(executor);
    scheduler.start();

    val sync = new CountDownLatch(1);
    scheduler.scheduleDirect(sync::countDown);
    val unending = new Runnable[1];
    unending[0] = () -> {
      scheduler.scheduleDirect(unending[0]);
    };
    unending[0].run();
    sync.await();

    executor.shutdownNow();
    assertTrue(executor.awaitTermination(DELAY, TimeUnit.MILLISECONDS),
        "Scheduler did not honor executor interruption.");
  }

  /**
   * Ensures that references to workers held after shutdown do not continue to add
   * tasks to the queue.
   */
  @Test
  public void testWorkerShutdown() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val value = new int[] { -1 };
    val worker1 = scheduler.createWorker();
    val sync1 = new CountDownLatch(1);
    threadPool.execute(() -> {
      sync1.countDown();
      while (true) {
        worker1.schedule(() -> value[0] = -1);
      }
    });
    sync1.await();

    scheduler.shutdown();
    scheduler.start();

    val worker2 = scheduler.createWorker();
    for (int i = 0; i < 10000; ++i) {
      final int snapshot = i;
      worker2.schedule(() -> {
        if (value[0] == -1) {
          value[0] = snapshot;
        }
      });
    }

    val sync2 = new CountDownLatch(1);
    worker2.schedule(sync2::countDown);
    sync2.await();
    assertEquals(0, value[0]);
  }

  /**
   * Ensure that there's not a case where rapid cancellation of tasks spuriously
   * looks like an empty queue.
   */
  @Test
  public void testTaskDisposalContention() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val sync = new Phaser(2);

    for (int i = 0; i < 100; ++i) {
      // Use 100 preceeding tasks per trial to increase the likelihood of hitting the
      // race. This race only occured when a task is cancelled between its initial
      // isDisposed() check and the fetching of its run implementation.
      val interference = new CompositeDisposable();
      for (int j = 0; j < 100; ++j) {
        interference.add(scheduler.scheduleDirect(Runnables.doNothing()));
      }
      scheduler.scheduleDirect(sync::arrive);
      interference.dispose();

      // In the error case, the above disposal confuses the scheduler into never
      // running the second task, and this never completes.
      sync.awaitAdvanceInterruptibly(sync.arrive());
    }
  }

  @Test
  public void testSameThreadDispatch() {
    val scheduler = new ContextScheduler(MoreExecutors.directExecutor());
    scheduler.start();

    val thread = new Thread[1];
    scheduler.scheduleDirect(() -> thread[0] = Thread.currentThread());
    assertEquals(Thread.currentThread(), thread[0]);
  }

  /**
   * Ensures that multiple threads scheduling tasks using caller-runs execution do
   * not run their tasks without mutual synchronization.
   */
  @Test
  public void testSameThreadSynchronization() throws Exception {
    val scheduler = new ContextScheduler(MoreExecutors.directExecutor());
    scheduler.start();

    final int THREADS = 2, ITERATIONS = 5000;

    val sync = new CountDownLatch(THREADS);
    val counter = new int[] { 0 };
    for (int i = 0; i < THREADS; ++i) {
      threadPool.execute(() -> {
        for (int j = 0; j < ITERATIONS; ++j) {
          scheduler.scheduleDirect(() -> ++counter[0]);
        }
        sync.countDown();
      });
    }
    sync.await();
    assertEquals(THREADS * ITERATIONS, counter[0]);
  }

  @Test
  public void testPauseWhileActive() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val counter = new int[] { 0 };
    final int ITERATIONS = 5000;
    for (int i = 0; i < ITERATIONS; ++i) {
      scheduler.scheduleDirect(() -> ++counter[0]);
    }
    for (int i = 0; i < ITERATIONS; ++i) {
      scheduler.pause();
      ++counter[0];
      scheduler.resume();
    }

    val sync = new CountDownLatch(1);
    scheduler.scheduleDirect(sync::countDown);
    sync.await();

    assertEquals(2 * ITERATIONS, counter[0]);
  }

  @Test
  public void testPauseWhileIdle() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val counter = new int[] { 0 };
    scheduler.pause();
    final int ITERATIONS = 5000;
    for (int i = 0; i < ITERATIONS; ++i) {
      scheduler.scheduleDirect(() -> ++counter[0]);
      ++counter[0];
    }
    val sync = new CountDownLatch(1);
    scheduler.scheduleDirect(sync::countDown);
    scheduler.resume();
    sync.await();
    assertEquals(2 * ITERATIONS, counter[0]);
  }

  @Test
  public void testNestedPause() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val sync = new CountDownLatch(1);
    scheduler.pause();
    scheduler.scheduleDirect(sync::countDown);
    scheduler.pause();
    scheduler.resume();
    assertFalse(sync.await(DELAY, TimeUnit.MILLISECONDS), "Dispatch resumed prematurely.");
    scheduler.resume();
    assertTrue(sync.await(DELAY, TimeUnit.MILLISECONDS));
  }

  @Test
  public void testPauseDelayed() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val delay = new CountDownLatch(1);
    scheduler.scheduleDirect(delay::countDown, 10 * DELAY, TimeUnit.MILLISECONDS);

    // Wait for the dispatch thread to be waiting.
    Thread.sleep(DELAY);
    final long start = System.currentTimeMillis();
    scheduler.pause();
    assertThat(System.currentTimeMillis() - start).isLessThan(DELAY);
  }

  @Test
  public void testPauseAndResumeFromDispatchThread() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val sync = new CountDownLatch(1);

    scheduler.scheduleDirect(() -> {
      scheduler.pause();
      scheduler.resume();
    });
    scheduler.scheduleDirect(sync::countDown);

    sync.await();
  }

  @Test
  public void testPreferDeferred() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val immediate = new CompletableFuture<Boolean>();
    scheduler.scheduleDirect(() -> {
      Observable.just(true).observeOn(scheduler).subscribe(immediate::complete);
      immediate.complete(false);
    });
    assertFalse(immediate.get());
  }

  @Test
  public void testPreferImmediate() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val immediate = new CompletableFuture<Boolean>();
    scheduler.scheduleDirect(() -> {
      Observable.just(true).observeOn(scheduler.preferImmediate()).subscribe(immediate::complete);
      immediate.complete(false);
    });
    assertTrue(immediate.get());
  }

  @Test
  public void testCannotImmediate() throws Exception {
    val scheduler = new ContextScheduler(threadPool);
    scheduler.start();

    val thread = new CompletableFuture<Thread>();
    Completable.complete().observeOn(scheduler.preferImmediate())
        .subscribe(() -> thread.complete(Thread.currentThread()));
    assertNotEquals(Thread.currentThread(), thread.get());
  }
}
