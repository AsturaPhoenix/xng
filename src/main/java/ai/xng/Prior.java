package ai.xng;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.val;

public interface Prior extends Node {
  final float THRESHOLD_MARGIN = .2f;
  final float DEFAULT_COEFFICIENT = ThresholdIntegrator.THRESHOLD + THRESHOLD_MARGIN;

  final long RAMP_UP = 5, RAMP_DOWN = 15;

  record Profile(Distribution coefficient, Integrator trace) {
  }

  Map<Posterior, Profile> getPosteriors();

  class Trait implements Prior {
    private static final long serialVersionUID = 1L;

    @Getter
    private final Map<Posterior, Profile> posteriors = new HashMap<>();
  }

  @Override
  default void activate() {
    for (val entry : getPosteriors().entrySet()) {
      final float sample = entry.getValue()
          .coefficient()
          .generate();
      entry.getKey()
          .getIntegrator()
          .add(RAMP_UP, RAMP_DOWN, sample);
      entry.getValue()
          .trace()
          .add(Scheduler.global.now(), RAMP_UP, RAMP_DOWN, sample);
    }
  }

  default void setCoefficient(final Posterior posterior, final float coefficient) {
    getPosteriors().compute(posterior, (__, profile) -> {
      if (profile == null) {
        return new Profile(new UnimodalHypothesis(coefficient), new Integrator());
      } else {
        profile.coefficient()
            .set(coefficient);
        return profile;
      }
    });
  }

  default void then(final Posterior posterior) {
    setCoefficient(posterior, DEFAULT_COEFFICIENT);
  }

  default void inhibit(final Posterior posterior) {
    setCoefficient(posterior, -1);
  }
}
