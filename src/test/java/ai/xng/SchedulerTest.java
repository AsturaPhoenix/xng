package ai.xng;

import static ai.xng.TestUtil.threadPool;
import static ai.xng.TestUtil.unchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import com.google.common.util.concurrent.Uninterruptibles;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.reactivex.disposables.CompositeDisposable;
import lombok.val;

@Timeout(1)
public class SchedulerTest {
  private static final long DELAY = 100;

  @Test
  public void testUnstartedIsNotOnThread() {
    assertFalse(new RealTimeScheduler(threadPool).isOnThread());
  }

  @Test
  public void testIsOnThread() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val isOnThread = new CompletableFuture<Boolean>();
    scheduler.postTask(() -> isOnThread.complete(scheduler.isOnThread()));
    assertTrue(isOnThread.get());
    assertFalse(scheduler.isOnThread());
  }

  @Test
  public void testDoesNotConsumeThreadBeforeTask() throws Exception {
    val singleThread = Executors.newSingleThreadExecutor();
    new RealTimeScheduler(singleThread);

    val sync = new CountDownLatch(1);
    singleThread.execute(sync::countDown);
    assertTrue(sync.await(DELAY, TimeUnit.MILLISECONDS));
  }

  @Test
  public void testCedesThreadWhileInactive() throws Exception {
    val singleThread = Executors.newSingleThreadExecutor();
    val scheduler = new RealTimeScheduler(singleThread);

    val sync = new Phaser(2);
    scheduler.postTask(sync::arrive);
    sync.awaitAdvanceInterruptibly(sync.arrive(), DELAY, TimeUnit.MILLISECONDS);

    singleThread.execute(sync::arrive);
    sync.awaitAdvanceInterruptibly(sync.arrive(), DELAY, TimeUnit.MILLISECONDS);
  }

  @Test
  public void testRejectionRecoveryOnDispatchStart() throws Exception {
    // single-threaded executor with no queue capacity
    val singleThread = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new SynchronousQueue<>());
    val scheduler = new RealTimeScheduler(singleThread);

    // Starve the thread pool before the scheduler requests a thread.
    val starve = new CountDownLatch(1);
    singleThread.execute(TestUtil.unchecked(starve::await));
    assertTrue(scheduler.postTask(Assertions::fail)
        .isDisposed());

    // Now make sure we gracefully recover if the executor allows it.
    starve.countDown();
    val sync = new CountDownLatch(1);
    assertTimeoutPreemptively(Duration.ofMillis(DELAY), () -> {
      // This needs to be a spin lock because we don't have a way to synchronize with
      // the thread pool.
      while (!Thread.interrupted() && scheduler.postTask(sync::countDown)
          .isDisposed())
        Thread.yield();
    });
    assertTrue(sync.await(DELAY, TimeUnit.MILLISECONDS));
  }

  /**
   * Verifies that an executing task blocks further tasks.
   */
  @Test
  public void testBlocking() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val blocking = new CountDownLatch(1);
    scheduler.postTask(unchecked(blocking::await));
    val waiting = new CountDownLatch(1);
    scheduler.postTask(waiting::countDown);

    assertFalse(waiting.await(DELAY, TimeUnit.MILLISECONDS));
    blocking.countDown();
    assertTrue(waiting.await(DELAY, TimeUnit.MILLISECONDS));
  }

  @Test
  public void testDeferredOnCurrentThread() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val immediate = new CompletableFuture<Boolean>();
    scheduler.postTask(() -> {
      scheduler.postTask(() -> immediate.complete(true));
      immediate.complete(false);
    });
    assertFalse(immediate.get());
  }

  @Test
  public void testFifo() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val reference = ContiguousSet.create(Range.closedOpen(0, 1000),
        DiscreteDomain.integers());
    for (int i = 0; i < 10; ++i) {
      val list = new ArrayList<Integer>();
      for (final int j : reference) {
        scheduler.postTask(() -> list.add(j));
      }

      val sync = new CountDownLatch(1);
      scheduler.postTask(sync::countDown);
      sync.await();

      assertThat(list).containsExactlyElementsOf(reference);
    }
  }

  @Test
  public void testRealTimeDelay() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val sync = new CountDownLatch(1);
    final long start = System.currentTimeMillis();
    scheduler.postTask(sync::countDown, scheduler.now() + DELAY);
    sync.await();
    assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(DELAY);
  }

  @Test
  public void testPreEmptDelayed() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val longDelay = new CountDownLatch(1);
    final long start = System.currentTimeMillis();
    scheduler.postTask(longDelay::countDown, scheduler.now() + 2 * DELAY);

    val shortDelay = new CountDownLatch(1);
    scheduler.postTask(shortDelay::countDown, scheduler.now() + DELAY);

    shortDelay.await();
    assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(DELAY);
    longDelay.await();
    assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(2 * DELAY);
  }

  @Test
  public void testOnThreadShutdown() throws Exception {
    val executor = Executors.newSingleThreadExecutor();
    val scheduler = new RealTimeScheduler(executor);
    val sync = new Phaser(2);
    scheduler.postTask(unchecked(() -> {
      sync.arriveAndAwaitAdvance();
      scheduler.shutdown();
      // Actually, the thread is interrupted at this point. It's unclear whether
      // that's for the best.
      sync.arriveAndAwaitAdvance();
    }));
    scheduler.postTask(sync::arrive);

    sync.arriveAndAwaitAdvance();
    sync.arriveAndAwaitAdvance();
    assertThrows(TimeoutException.class,
        () -> sync.awaitAdvanceInterruptibly(sync.arrive(), DELAY, TimeUnit.MILLISECONDS));

    val threadInterrupted = new CompletableFuture<Boolean>();
    executor.submit(() -> threadInterrupted.complete(Thread.interrupted()));
    // Actually, it appears as if the executor is resetting the interrupted status
    // for us. It's unclear if that's for the best.
    assertFalse(threadInterrupted.get(), "Unexpected interrupted status on relinquished thread.");
  }

  @Test
  public void testPauseDuringShutdown() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val sync = new CountDownLatch(1);
    scheduler.postTask(() -> {
      sync.countDown();
      Uninterruptibles.sleepUninterruptibly(DELAY, TimeUnit.MILLISECONDS);
    });

    sync.await();
    scheduler.shutdown();
    scheduler.pause();
  }

  @Test
  public void testThreadPoolShutdownPendingDelayed() throws Exception {
    val executor = Executors.newSingleThreadExecutor();
    val scheduler = new RealTimeScheduler(executor);

    val sync = new CountDownLatch(1);
    scheduler.postTask(sync::countDown);
    scheduler.postTask(Runnables.doNothing(), scheduler.now() + 10000);
    sync.await();

    executor.shutdownNow();
    assertTrue(executor.awaitTermination(DELAY, TimeUnit.MILLISECONDS),
        "Scheduler did not honor executor interruption.");
  }

  @Test
  public void testThreadPoolShutdownUnendingTasks() throws Exception {
    val executor = Executors.newSingleThreadExecutor();
    val scheduler = new RealTimeScheduler(executor);

    val sync = new CountDownLatch(1);
    scheduler.postTask(sync::countDown);
    val unending = new Runnable[1];
    unending[0] = () -> {
      scheduler.postTask(unending[0]);
    };
    unending[0].run();
    sync.await();

    executor.shutdownNow();
    assertTrue(executor.awaitTermination(DELAY, TimeUnit.MILLISECONDS),
        "Scheduler did not honor executor interruption.");
  }

  /**
   * Ensure that there's not a case where rapid cancellation of tasks spuriously
   * looks like an empty queue.
   */
  @Test
  public void testTaskDisposalContention() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val sync = new Phaser(2);

    for (int i = 0; i < 100; ++i) {
      // Use 100 preceeding tasks per trial to increase the likelihood of hitting the
      // race. This race only occured when a task is cancelled between its initial
      // isDisposed() check and the fetching of its run implementation.
      val interference = new CompositeDisposable();
      for (int j = 0; j < 100; ++j) {
        interference.add(scheduler.postTask(Runnables.doNothing()));
      }
      scheduler.postTask(sync::arrive);
      interference.dispose();

      // In the error case, the above disposal confuses the scheduler into never
      // running the second task, and this never completes.
      sync.awaitAdvanceInterruptibly(sync.arrive());
    }
  }

  @Test
  public void testSameThreadDispatch() {
    val scheduler = new RealTimeScheduler(MoreExecutors.directExecutor());

    val thread = new Thread[1];
    scheduler.postTask(() -> thread[0] = Thread.currentThread());
    assertEquals(Thread.currentThread(), thread[0]);
  }

  /**
   * Ensures that multiple threads scheduling tasks using caller-runs execution do
   * not run their tasks without mutual synchronization.
   */
  @Test
  public void testSameThreadSynchronization() throws Exception {
    val scheduler = new RealTimeScheduler(MoreExecutors.directExecutor());

    final int THREADS = 2, ITERATIONS = 5000;

    val sync = new CountDownLatch(THREADS);
    val counter = new int[] { 0 };
    for (int i = 0; i < THREADS; ++i) {
      threadPool.execute(() -> {
        for (int j = 0; j < ITERATIONS; ++j) {
          scheduler.postTask(() -> ++counter[0]);
        }
        sync.countDown();
      });
    }
    sync.await();
    assertEquals(THREADS * ITERATIONS, counter[0]);
  }

  @Test
  public void testPauseWhileActive() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val counter = new int[] { 0 };
    final int ITERATIONS = 5000;
    for (int i = 0; i < ITERATIONS; ++i) {
      scheduler.postTask(() -> ++counter[0]);
    }
    for (int i = 0; i < ITERATIONS; ++i) {
      scheduler.pause();
      ++counter[0];
      scheduler.resume();
    }

    val sync = new CountDownLatch(1);
    scheduler.postTask(sync::countDown);
    sync.await();

    assertEquals(2 * ITERATIONS, counter[0]);
  }

  @Test
  public void testPauseWhileIdle() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val counter = new int[] { 0 };
    scheduler.pause();
    final int ITERATIONS = 5000;
    for (int i = 0; i < ITERATIONS; ++i) {
      scheduler.postTask(() -> ++counter[0]);
      ++counter[0];
    }
    val sync = new CountDownLatch(1);
    scheduler.postTask(sync::countDown);
    scheduler.resume();
    sync.await();
    assertEquals(2 * ITERATIONS, counter[0]);
  }

  @Test
  public void testNestedPause() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val sync = new CountDownLatch(1);
    scheduler.pause();
    scheduler.postTask(sync::countDown);
    scheduler.pause();
    scheduler.resume();
    assertFalse(sync.await(DELAY, TimeUnit.MILLISECONDS), "Dispatch resumed prematurely.");
    scheduler.resume();
    assertTrue(sync.await(DELAY, TimeUnit.MILLISECONDS));
  }

  @Test
  public void testPauseDelayed() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val delay = new CountDownLatch(1);
    scheduler.postTask(delay::countDown, scheduler.now() + 10 * DELAY);

    // Wait for the dispatch thread to be waiting.
    Thread.sleep(DELAY);
    final long start = System.currentTimeMillis();
    scheduler.pause();
    assertThat(System.currentTimeMillis() - start).isLessThan(DELAY);
  }

  @Test
  public void testPauseAndResumeFromDispatchThread() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val sync = new CountDownLatch(1);

    scheduler.postTask(() -> {
      scheduler.pause();
      scheduler.resume();
    });
    scheduler.postTask(sync::countDown);

    sync.await();
  }

  @Test
  public void testColdOrdering() throws Exception {
    val scheduler = new RealTimeScheduler(threadPool);

    val flag = new boolean[] { false };
    for (int i = 0; i < 100000; ++i) {
      val flagResult = new CompletableFuture<Boolean>();
      scheduler.postTask(() -> flag[0] = true);
      scheduler.postTask(() -> flagResult.complete(flag[0]));
      assertTrue(flagResult.get());
      flag[0] = false;
    }
  }
}
