package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.val;
import lombok.experimental.Accessors;

public interface Prior extends Node {
  final float THRESHOLD_MARGIN = .2f;
  final float DEFAULT_COEFFICIENT = ThresholdIntegrator.THRESHOLD + THRESHOLD_MARGIN;

  final long RAMP_UP = 5, RAMP_DOWN = 15;

  @Accessors(fluent = true)
  class Profile implements Serializable {
    private static final long serialVersionUID = 1L;

    @Getter
    private final Distribution coefficient;
    @Getter
    private transient Integrator trace;

    public Profile(final float coefficient) {
      this.coefficient = new UnimodalHypothesis(coefficient);
      init();
    }

    private void init() {
      trace = new Integrator();
    }

    private void readObject(final ObjectInputStream stream) throws IOException, ClassNotFoundException {
      stream.defaultReadObject();
      init();
    }
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
          .add(Scheduler.global.now(), RAMP_UP, RAMP_DOWN, 1);
    }
  }

  default void setCoefficient(final Posterior posterior, final float coefficient) {
    getPosteriors().compute(posterior, (__, profile) -> {
      if (profile == null) {
        return new Profile(coefficient);
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
