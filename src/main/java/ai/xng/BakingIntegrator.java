package ai.xng;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.val;

public class BakingIntegrator {
  @AllArgsConstructor
  public static class Segment {
    public long t0, t1;
    public float v0, rate;

    public float evaluate(final long t) {
      return v0 + rate * (t - t0);
    }

    public long duration() {
      return t1 - t0;
    }

    @Override
    public String toString() {
      return String.format("(%s, %.2f)-(%s, %.2f)", t0, v0, t1, evaluate(t1));
    }
  }

  public class Spike {
    public final Segment rampUp, rampDown;

    public Spike(final long t, final IntegrationProfile profile, final float magnitude) {
      rampUp = new Segment(t + profile.delay(), t + profile.peak(), 0, magnitude / profile.rampUp());
      rampDown = new Segment(rampUp.t1, t + profile.period(), magnitude, -magnitude / profile.rampDown());

      add(rampUp);
      add(rampDown);
    }

    public long end() {
      return rampDown.t1;
    }

    public void clear() {
      remove(rampUp);
      remove(rampDown);
    }
  }

  public static record Ray(float value, float rate) {
  }

  private final List<Segment> segments = new ArrayList<>();

  public void add(final Segment segment) {
    segments.add(segment);
  }

  public void remove(final Segment segment) {
    segments.remove(segment);
  }

  public void evict(final long t) {
    segments.removeIf(s -> s.t1 <= t);
  }

  public Ray evaluate(final long t) {
    float vt = 0, rate = 0;
    for (val s : segments) {
      if (s.t0 <= t && t < s.t1) {
        vt += s.evaluate(t);
        rate += s.rate;
      }
    }
    return new Ray(vt, rate);
  }

  public Optional<Long> nextCriticalPoint(final long t) {
    // We can probably optimize by returning the value at the critical point as
    // well, but using that value can complicate the caller logic.
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

  @Override
  public String toString() {
    return segments.toString();
  }
}