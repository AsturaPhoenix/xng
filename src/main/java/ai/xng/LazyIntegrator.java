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

  /**
   * Evaluates the integrator at time {@code t} where the time series encoded in
   * this integrator is interpreted as spikes of the given {@code profile}. Each
   * spike begins (with possible delay) at each time series point.
   */
  public float evaluate(final long t, final IntegrationProfile profile) {
    float value = 0;
    for (val sample : samples) {
      final long dt = t - sample.time();
      if (dt > profile.delay()) {
        if (dt < profile.peak()) {
          value += sample.value() * (dt - profile.delay()) / profile.rampUp();
        } else if (dt < profile.period()) {
          value += sample.value() * (profile.period() - dt) / profile.rampDown();
        }
      }
    }

    return value;
  }

  /**
   * Removes all samples with start points before {@code t}. Notably, this differs
   * from the behavior of {@link BakingIntegrator#evict(long)} as the segment
   * length is not known here.
   * <p>
   * The condition is before rather than at or before to side-step a potential
   * race condition when used to evict before "now".
   */
  public void evict(final long t) {
    samples.removeIf(s -> s.time() < t);
  }
}