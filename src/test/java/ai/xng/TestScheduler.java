package ai.xng;

import java.util.Optional;
import java.util.PriorityQueue;

import com.google.common.collect.ComparisonChain;

import io.reactivex.disposables.Disposable;
import lombok.AllArgsConstructor;
import lombok.val;

public class TestScheduler extends Scheduler {
  @AllArgsConstructor
  private class Task implements Comparable<Task>, Disposable {
    final long deadline, sequenceNumber;
    final Runnable run;

    @Override
    public int compareTo(final Task o) {
      return ComparisonChain.start()
          .compare(deadline, o.deadline)
          .compare(sequenceNumber, o.sequenceNumber)
          .result();
    }

    @Override
    public void dispose() {
      tasks.remove(this);
    }

    @Override
    public boolean isDisposed() {
      return !tasks.contains(this);
    }
  }

  private long now = 0, sequenceNumber = Long.MIN_VALUE;
  private final PriorityQueue<Task> tasks = new PriorityQueue<>();

  @Override
  public long now() {
    return now;
  }

  @Override
  public Disposable postTask(final Runnable run, final long deadline) {
    val task = new Task(deadline, sequenceNumber++, run);
    tasks.add(task);
    return task;
  }

  @Override
  public Disposable postTask(final Runnable run) {
    return postTask(run, now());
  }

  public Optional<Long> step() {
    if (!tasks.isEmpty() && tasks.peek().deadline <= now) {
      tasks.poll().run.run();
    }

    return Optional.ofNullable(tasks.peek())
        .map(t -> t.deadline);
  }

  public void runUntil(final long time) {
    while (now < time) {
      now = Math.max(now,
          step().map(t -> Math.min(t, time))
              .orElse(time));
    }
  }

  public void runUntilIdle() {
    Optional<Long> next;
    while ((next = step()).isPresent()) {
      now = Math.max(now, next.get());
    }
  }
}