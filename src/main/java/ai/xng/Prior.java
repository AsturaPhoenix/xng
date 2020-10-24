package ai.xng;

import java.io.Serializable;

import lombok.Getter;
import lombok.val;

public interface Prior extends Node {
  float THRESHOLD_MARGIN = .2f;
  float DEFAULT_COEFFICIENT = ThresholdIntegrator.THRESHOLD + THRESHOLD_MARGIN;

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

      val it = posteriors.iterator();
      while (it.hasNext()) {
        val posterior = it.next();
        // LTD due to reverse STDP
        posterior.distribution()
            .reinforce(-posterior.node().getTrace().evaluate(now, posterior.profile())
                * posterior.node().getCluster().getPlasticity());

        if (posterior.distribution().getWeight() == 0) {
          it.remove();
        } else {
          final float sample = posterior.distribution().generate();
          posterior.node().getIntegrator().add(posterior.profile(), sample);
        }
      }
    }
  }

  default <T extends Posterior> T then(final T posterior) {
    getPosteriors().getDistribution(posterior, IntegrationProfile.TRANSIENT).set(DEFAULT_COEFFICIENT);
    return posterior;
  }

  default void inhibit(final Posterior posterior) {
    getPosteriors().getDistribution(posterior, IntegrationProfile.TRANSIENT).set(-1);
  }
}
