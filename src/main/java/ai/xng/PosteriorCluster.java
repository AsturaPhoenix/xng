package ai.xng;

import java.io.Serializable;

import lombok.Getter;

public interface PosteriorCluster<T extends Posterior> extends Cluster<T> {
  float DEFAULT_PLASTICITY = .1f;

  float getPlasticity();

  void setPlasticity(float plasticity);

  class Trait implements Serializable {
    private static final long serialVersionUID = 1L;

    @Getter
    private float plasticity = DEFAULT_PLASTICITY;

    public void setPlasticity(final float plasticity) {
      if (plasticity < 0 || plasticity > 1) {
        throw new IllegalArgumentException(String.format("Plasticity (%s) must be [0, 1].", plasticity));
      }
      this.plasticity = plasticity;
    }
  }
}
