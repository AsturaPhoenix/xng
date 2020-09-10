package ai.xng;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.val;

public interface Prior extends Node {
  /**
   * Margin by which to pad on either side of a threshold for default conjunctive
   * or disjunctive edges.
   */
  final float THRESHOLD_MARGIN = .2f;
  final float DEFAULT_COEFFICIENT = ThresholdIntegrator.THRESHOLD + THRESHOLD_MARGIN;

  final long RAMP_UP = 5, RAMP_DOWN = 15;

  Map<Posterior, Distribution> getPosteriors();

  class Trait implements Prior {
    private static final long serialVersionUID = 1L;

    @Getter
    private final Map<Posterior, Distribution> posteriors = new HashMap<>();
  }

  @Override
  default void activate() {
    for (val entry : getPosteriors().entrySet()) {
      entry.getKey()
          .getIntegrator()
          .add(RAMP_UP, RAMP_DOWN, entry.getValue()
              .generate());
    }
  }

  default void setCoefficient(final Posterior posterior, final float coefficient) {
    getPosteriors().compute(posterior, (__, distribution) -> {
      if (distribution == null) {
        return new UnimodalHypothesis(coefficient);
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
