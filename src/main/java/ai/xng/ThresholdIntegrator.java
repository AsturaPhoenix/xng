package ai.xng;

import java.util.Optional;

import io.reactivex.disposables.Disposable;
import lombok.val;

public abstract class ThresholdIntegrator {
  public static final float THRESHOLD = 1;
  public static final long REINFORCEMENT_TTL = 15000;

  private static record TimeSeries<T> (T item, long time) {
  }

  private final Integrator integrator = new Integrator();

  private TimeSeries<Disposable> nextThreshold;

  protected abstract void onThreshold();

  public void add(final long rampUp, final long rampDown, final float magnitude) {
    evict();
    integrator.add(Scheduler.global.now(), rampUp, rampDown, magnitude);

    final Optional<Long> updatedNextThreshold = nextThreshold(Scheduler.global.now());

    if (nextThreshold == null || updatedNextThreshold.map(t -> t != nextThreshold.time())
        .orElse(true)) {
      if (nextThreshold != null) {
        nextThreshold.item.dispose();
      }
      schedule(updatedNextThreshold);
    }
  }

  public Optional<Long> nextThreshold(long t) {
    while (true) {
      val trajectory = integrator.evaluate(t);
      val nextCriticalPoint = integrator.nextCriticalPoint(t);

      if (trajectory.value() < THRESHOLD) {
        if (trajectory.rate() > 0) {
          final long intercept = t + (long) Math.ceil((THRESHOLD - trajectory.value()) / trajectory.rate());
          if (nextCriticalPoint.map(tc -> intercept <= tc)
              .orElse(true)) {
            return Optional.of(intercept);
          }
        }
      }

      if (nextCriticalPoint.isEmpty())
        break;
      t = nextCriticalPoint.get();
    }

    return Optional.empty();
  }

  private void evict() {
    integrator.evict(Scheduler.global.now() - REINFORCEMENT_TTL);
  }

  private void schedule(final Optional<Long> tOpt) {
    nextThreshold = tOpt.map(t -> new TimeSeries<Disposable>(Scheduler.global.postTask(() -> {
      onThreshold();
      evict();
      schedule(nextThreshold(Scheduler.global.now()));
    }, t), t))
        .orElse(null);
  }
}