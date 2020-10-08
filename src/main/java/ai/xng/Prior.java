package ai.xng;

import java.io.Serializable;

import lombok.Getter;
import lombok.val;

public interface Prior extends Node {
  final float THRESHOLD_MARGIN = .2f;
  final float DEFAULT_COEFFICIENT = ThresholdIntegrator.THRESHOLD + THRESHOLD_MARGIN;

  final long RAMP_UP = 5, RAMP_DOWN = 45;

  ConnectionMap.PosteriorMap getPosteriors();

  class Trait implements Serializable {
    private static final long serialVersionUID = 1L;

    @Getter
    private final ConnectionMap.PosteriorMap posteriors;

    public Trait(final Prior owner) {
      posteriors = new ConnectionMap.PosteriorMap(owner);
    }

    public void activate() {
      for (val entry : getPosteriors().entrySet()) {
        final float sample = entry.getValue()
            .generate();
        // TODO: adaptation/depletion
        entry.getKey()
            .getIntegrator()
            .add(RAMP_UP, RAMP_DOWN, sample);
      }
    }
  }

  default void setCoefficient(final Posterior posterior, final float coefficient) {
    getPosteriors().compute(posterior, (__, profile) -> {
      if (profile == null) {
        profile = new UnimodalHypothesis(coefficient);
        posterior.getPriors()
            .put(this, profile);
        return profile;
      } else {
        profile.set(coefficient);
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
