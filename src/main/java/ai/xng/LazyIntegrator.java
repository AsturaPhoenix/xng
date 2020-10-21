package ai.xng;

import java.util.ArrayList;
import java.util.List;

import ai.xng.util.TimeSeries;
import lombok.val;

public class LazyIntegrator {
  private final List<TimeSeries<Float>> samples = new ArrayList<>();

  public void add(final long start, final float magnitude) {
    samples.add(new TimeSeries<>(magnitude, start));
  }

  public float evaluate(final long t, final IntegrationProfile profile) {
    float value = 0;
    for (val sample : samples) {
      final long dt = t - sample.time();
      if (dt > 0) {
        if (dt < profile.rampUp()) {
          value += sample.value() * dt / profile.rampUp();
        } else if (dt < profile.period()) {
          value += sample.value() * (profile.period() - dt) / profile.rampDown();
        }
      }
    }

    return value;
  }

  /**
   * Removes all samples with start points at or before {@code t}. Notably, this
   * differs from the behavior of {@link BakingIntegrator#evict(long)} as the
   * segment length is not known here.
   */
  public void evict(final long t) {
    samples.removeIf(s -> s.time() <= t);
  }
}