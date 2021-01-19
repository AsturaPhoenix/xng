package ai.xng;

import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor
public class ConjunctionJunction {
  private static record Component(Prior prior, IntegrationProfile profile, float weight) {
  }

  private final List<Component> components = new ArrayList<>();
  // Norm keeps a projected summation under the delay before posterior activation
  // captured during training. However, this can be unrealistic if the maximum
  // sum, which would actually trigger activation, occurs well earlier, leading to
  // conjunctions that are too lenient. Keeping the max recorded component weight
  // allows us to compensate for this by scaling the norm projection so that the
  // max component effectively becomes 1.
  private float norm, maxComponent;

  public ConjunctionJunction addAll(final Iterable<? extends Prior> priors) {
    for (val prior : priors) {
      add(prior);
    }
    return this;
  }

  public ConjunctionJunction add(final Prior prior) {
    return add(prior, IntegrationProfile.TRANSIENT, 1);
  }

  public ConjunctionJunction add(final Prior prior, final IntegrationProfile profile, final float weight) {
    if (weight > 0) {
      components.add(new Component(prior, profile, weight));
      norm += weight * weight;
      if (weight > maxComponent) {
        maxComponent = weight;
      }
    }
    return this;
  }

  public <T extends Posterior> T build(final T posterior) {
    return build(posterior, 1);
  }

  public <T extends Posterior> T build(final T posterior, final float weight) {
    // Scale such that activation of the last principal component (may be
    // hypothetical, with relative weight 1) roughly has margins on either side of
    // the activation threshold (but cap the maximum at the default coefficient, and
    // never increase coefficients as a result of normalization).
    //
    // The actual formulation of the above, (norm - 1) / (1 - .5 / norm), doesn't
    // work as well in the presence of less significant components as the below, as
    // it produces false positives.
    float normAdj = norm / maxComponent;
    normAdj = normAdj <= Prior.DEFAULT_COEFFICIENT ? 1
        : Math.max(normAdj / Prior.DEFAULT_COEFFICIENT, normAdj - .5f / normAdj);

    for (val component : components) {
      final float coefficient = component.weight() / normAdj;
      assert coefficient <= Prior.DEFAULT_COEFFICIENT;
      component.prior().getPosteriors().getDistribution(posterior, component.profile()).add(coefficient, weight);
    }

    return posterior;
  }
}
