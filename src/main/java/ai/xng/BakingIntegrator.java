package ai.xng;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.val;

public class BakingIntegrator {
  private static record Segment(long t0, long t1, float v0, float rate) {
  }

  public static record Ray(float value, float rate) {
  }

  private final List<Segment> segments = new ArrayList<>();

  public void add(final long start, final IntegrationProfile profile, final float magnitude) {
    final long peak = start + profile.peak();
    segments.add(new Segment(start + profile.delay(), peak, 0, magnitude / profile.rampUp()));
    segments.add(new Segment(peak, peak + profile.rampDown(), magnitude, -magnitude / profile.rampDown()));
  }

  public void evict(final long t) {
    segments.removeIf(s -> s.t1() <= t);
  }

  public Ray evaluate(final long t) {
    float vt = 0, rate = 0;
    for (val s : segments) {
      if (s.t0 <= t && t < s.t1) {
        vt += s.v0 + s.rate * (t - s.t0);
        rate += s.rate;
      }
    }
    return new Ray(vt, rate);
  }

  public Optional<Long> nextCriticalPoint(final long t) {
    Optional<Long> next = Optional.empty();
    for (val s : segments) {
      Optional<Long> candidate = Optional.empty();
      if (s.t0 > t) {
        candidate = Optional.of(s.t0);
      } else if (s.t1 > t) {
        candidate = Optional.of(s.t1);
      }
      if (candidate.isPresent() && (!next.isPresent() || candidate.get() < next.get())) {
        next = candidate;
      }
    }
    return next;
  }
}