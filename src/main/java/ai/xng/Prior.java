package ai.xng;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.val;

public interface Prior extends Node {
  final float THRESHOLD_MARGIN = .2f;
  final float DEFAULT_COEFFICIENT = ThresholdIntegrator.THRESHOLD + THRESHOLD_MARGIN;

  final long RAMP_UP = 5, RAMP_DOWN = 50;

  Map<Posterior, Distribution> getPosteriors();

  class Trait implements Serializable {
    private static final long serialVersionUID = 1L;

    @Getter
    private final Map<Posterior, Distribution> posteriors = new HashMap<>();

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
    getPosteriors().compute(posterior, (__, distribution) -> {
      if (distribution == null) {
        distribution = new UnimodalHypothesis(coefficient);
        posterior.getPriors()
            .put(this, distribution);
        return distribution;
      } else {
        distribution.set(coefficient);
        return distribution;
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
