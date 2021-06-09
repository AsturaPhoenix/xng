package ai.xng;

import lombok.Getter;

public abstract class PosteriorCluster<T extends Posterior> extends Cluster<T> {
  /**
   * Plasticity is disabled for now until requirements become clearer. It's likely
   * that the STDP behavior needs to be adjusted to penalize connections that do
   * not trigger. However, it is also possible that explicit plasticity in the
   * generalized capture mechanism is preferable to "always-on" plasticity in this
   * manner.
   */
  public static final float DEFAULT_PLASTICITY = 0;

  @Getter
  private float plasticity = DEFAULT_PLASTICITY;

  public void setPlasticity(final float plasticity) {
    if (plasticity < 0 || plasticity > 1) {
      throw new IllegalArgumentException(String.format("Plasticity (%s) must be [0, 1].", plasticity));
    }
    this.plasticity = plasticity;
  }
}
