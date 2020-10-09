package ai.xng;

import java.io.Serializable;

import lombok.Getter;
import lombok.val;

public interface Prior extends Node {
  float THRESHOLD_MARGIN = .2f;
  float DEFAULT_COEFFICIENT = ThresholdIntegrator.THRESHOLD + THRESHOLD_MARGIN;

  long RAMP_UP = 5, RAMP_DOWN = 45;
  float LTD_STRENGTH = .1f;

  Connections.Posteriors getPosteriors();

  class Trait implements Serializable {
    private static final long serialVersionUID = 1L;

    @Getter
    private final Connections.Posteriors posteriors;

    public Trait(final Prior owner) {
      posteriors = new Connections.Posteriors(owner);
    }

    public void activate() {
      final long now = Scheduler.global.now();

      for (val posterior : posteriors) {
        // LTD due to reverse STDP
        posterior.distribution()
            .add(
                posterior.distribution()
                    .getMode(),
                -posterior.node()
                    .getTrace()
                    .evaluate(now)
                    .value() * posterior.node()
                        .getCluster()
                        .getPlasticity());

        final float sample = posterior.distribution()
            .generate();
        posterior.node()
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
