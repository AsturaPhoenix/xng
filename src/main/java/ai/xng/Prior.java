package ai.xng;

import java.io.Serializable;

import lombok.Getter;
import lombok.val;

public interface Prior extends Node {
  final float THRESHOLD_MARGIN = .2f;
  final float DEFAULT_COEFFICIENT = ThresholdIntegrator.THRESHOLD + THRESHOLD_MARGIN;

  final long RAMP_UP = 5, RAMP_DOWN = 45;

  Connections.Posteriors getPosteriors();

  class Trait implements Serializable {
    private static final long serialVersionUID = 1L;

    @Getter
    private final Connections.Posteriors posteriors;

    public Trait(final Prior owner) {
      posteriors = new Connections.Posteriors(owner);
    }

    public void activate() {
      for (val entry : posteriors) {
        final float sample = entry.distribution()
            .generate();
        // TODO: adaptation/depletion
        entry.node()
            .getIntegrator()
            .add(RAMP_UP, RAMP_DOWN, sample);
      }
    }
  }

  default <T extends Posterior> T then(final T posterior) {
    getPosteriors().setCoefficient(posterior, DEFAULT_COEFFICIENT);
    return posterior;
  }

  default void inhibit(final Posterior posterior) {
    getPosteriors().setCoefficient(posterior, -1);
  }
}
