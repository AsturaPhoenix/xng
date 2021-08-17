package ai.xng;

import java.util.Optional;

import ai.xng.util.TimeSeries;
import io.reactivex.disposables.Disposable;
import lombok.val;

public abstract class ThresholdIntegrator {
  public static final float THRESHOLD = 1;

  public class Spike extends BakingIntegrator.Spike {
    private Spike(final IntegrationProfile profile, final float magnitude) {
      integrator.super(Scheduler.global.now(), profile, magnitude);
    }

    /**
     * Modifies the spike to use a new ramp-up rate. If a ramp-up is already in
     * progress, the ramp-up segment is moved to begin at its current coordinate.
     * There is no effect if the peak has already passed.
     */
    public void adjustRampUp(final float newRate) {
      final long now = Scheduler.global.now();
      if (now < rampUp.t1) {
        if (now > rampUp.t0) {
          rampUp.v0 = rampUp.evaluate(now);
        }
        rampUp.rate = newRate;
        rampDown.v0 = rampUp.evaluate(rampUp.t1);
        rampDown.rate = -rampDown.v0 / rampDown.duration();
        invalidate();
      }
    }

    public void clear() {
      super.clear();
      invalidate();
    }
  }

  private final BakingIntegrator integrator = new BakingIntegrator();

  private TimeSeries<Disposable> nextThreshold;

  /**
   * Gets the timestamp of the currently scheduled next threshold.
   */
  public Optional<Long> nextThreshold() {
    return Optional.ofNullable(nextThreshold).map(TimeSeries::time);
  }

  /**
   * Returns whether there is currently a due threshold processing task scheduled.
   */
  public boolean isPending() {
    return nextThreshold().map(t -> t <= Scheduler.global.now()).orElse(false);
  }

  public float getValue() {
    return getValue(Scheduler.global.now());
  }

  public float getValue(final long t) {
    return integrator.evaluate(t).value();
  }

  public float getNormalizedCappedValue() {
    val value = getValue();
    return value >= THRESHOLD ? 1 : value / THRESHOLD;
  }

  protected abstract void onThreshold();

  public Spike add(final IntegrationProfile profile, final float magnitude) {
    evict();
    val spike = new Spike(profile, magnitude);
    invalidate();
    return spike;
  }

  private void invalidate() {
    val now = Scheduler.global.now();
    if (nextThreshold != null && nextThreshold.time() == now) {
      return;
    }

    final Optional<Long> updatedNextThreshold = nextThreshold(now, 1);
    if (nextThreshold == null || updatedNextThreshold.map(t -> t != nextThreshold.time())
        .orElse(true)) {
      if (nextThreshold != null) {
        nextThreshold.value().dispose();
      }
      schedule(updatedNextThreshold);
    }
  }

  /**
   * @param direction 1 for rising, -1 for falling
   */
  public Optional<Long> nextThreshold(long t, final int direction) {
    while (true) {
      val trajectory = integrator.evaluate(t);
      val nextCriticalPoint = integrator.nextCriticalPoint(t);

      if (Math.signum(Float.compare(THRESHOLD, trajectory.value())) == direction) {
        if (Math.signum(trajectory.rate()) == direction) {
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
    return getValue() >= THRESHOLD;
  }

  private void evict() {
    integrator.evict(Scheduler.global.now());
  }

  private void schedule(final Optional<Long> tOpt) {
    nextThreshold = tOpt.map(t -> new TimeSeries<Disposable>(Scheduler.global.postTask(() -> {
      onThreshold();
      evict();
      schedule(nextThreshold(Scheduler.global.now(), 1));
    }, t), t))
        .orElse(null);
  }

  @Override
  public String toString() {
    return integrator.evaluate(Scheduler.global.now()) + ": " + integrator.toString();
  }
}