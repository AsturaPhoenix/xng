package ai.xng;

import java.util.Optional;

import ai.xng.util.TimeSeries;
import io.reactivex.disposables.Disposable;
import lombok.val;

public abstract class ThresholdIntegrator {
  public static final float THRESHOLD = 1;

  private final BakingIntegrator integrator = new BakingIntegrator();

  private TimeSeries<Disposable> nextThreshold;

  protected abstract void onThreshold();

  public void add(final IntegrationProfile profile, final float magnitude) {
    evict();
    integrator.add(Scheduler.global.now(), profile, magnitude);

    final Optional<Long> updatedNextThreshold = nextThreshold(Scheduler.global.now());

    if (nextThreshold == null || updatedNextThreshold.map(t -> t != nextThreshold.time())
        .orElse(true)) {
      if (nextThreshold != null) {
        nextThreshold.value().dispose();
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

  public boolean isActive() {
    return integrator.evaluate(Scheduler.global.now())
        .value() >= THRESHOLD;
  }

  private void evict() {
    integrator.evict(Scheduler.global.now());
  }

  private void schedule(final Optional<Long> tOpt) {
    nextThreshold = tOpt.map(t -> new TimeSeries<Disposable>(Scheduler.global.postTask(() -> {
      onThreshold();
      evict();
      schedule(nextThreshold(Scheduler.global.now()));
    }, t), t))
        .orElse(null);
  }

  @Override
  public String toString() {
    return integrator.evaluate(Scheduler.global.now()) + ": " + integrator.toString();
  }
}