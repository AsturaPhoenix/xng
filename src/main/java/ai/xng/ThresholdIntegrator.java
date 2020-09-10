package ai.xng;

import java.util.Optional;

import io.reactivex.disposables.Disposable;
import lombok.val;

public abstract class ThresholdIntegrator {
  public static final float THRESHOLD = 1;

  private static record TimeSeries<T> (T item, long time) {
  }

  private final Integrator integrator = new Integrator();

  private TimeSeries<Disposable> nextEvaluation;

  protected abstract void onThreshold();

  public void add(final long rampUp, final long rampDown, final float magnitude) {
    integrator.add(Scheduler.global.now(), rampUp, rampDown, magnitude);

    final TimeSeries<Runnable> updatedNextEvaluation = nextEvaluation().get();

    if (nextEvaluation != null) {
      // TODO: This results in some unnecessary churn.
      nextEvaluation.item.dispose();
    }
    nextEvaluation = scheduleEvaluation(updatedNextEvaluation);
  }

  private Optional<TimeSeries<Runnable>> nextEvaluation() {
    final long now = Scheduler.global.now();

    integrator.evict(now);

    val trajectory = integrator.evaluate(now);
    val nextCriticalPoint = integrator.nextCriticalPoint(now);

    if (trajectory.value() < THRESHOLD) {
      if (trajectory.rate() > 0) {
        final long intercept = now + (long) Math.ceil((THRESHOLD - trajectory.value()) / trajectory.rate());
        if (nextCriticalPoint.map(t -> intercept <= t)
            .orElse(true)) {
          return Optional.of(new TimeSeries<Runnable>(() -> {
            onThreshold();
            scheduleNextEvaluation();
          }, intercept));
        }
      }
    }

    return nextCriticalPoint.map(t -> new TimeSeries<Runnable>(this::scheduleNextEvaluation, t));
  }

  private void scheduleNextEvaluation() {
    nextEvaluation = nextEvaluation()
        .map(this::scheduleEvaluation)
        .orElse(null);
  }

  private TimeSeries<Disposable> scheduleEvaluation(final TimeSeries<Runnable> task) {
    return new TimeSeries<Disposable>(Scheduler.global.postTask(task.item, task.time), task.time);
  }
}