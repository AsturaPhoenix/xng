package ai.xng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor
public class ConjunctionJunction {
  public static class Pure {
    private final List<Prior> priors = new ArrayList<>();

    public Pure add(final Prior prior) {
      priors.add(prior);
      return this;
    }

    public Pure addAll(final Collection<? extends Prior> priors) {
      this.priors.addAll(priors);
      return this;
    }

    public void build(final Posterior posterior) {
      final float coefficient = priors.size() <= 1 ? Prior.DEFAULT_COEFFICIENT
          : 1 / (priors.size() - .5f / priors.size());

      for (val prior : priors) {
        prior.getPosteriors().setCoefficient(posterior, coefficient);
      }
    }
  }

  private static record WeightedPrior(Prior prior, float weight) {
  }

  private final List<WeightedPrior> priors = new ArrayList<>();
  private final long t;

  private float norm;

  public void add(final Prior prior) {
    final float w = prior.getTrace()
        .evaluate(t)
        .value();
    if (w > 0) {
      priors.add(new WeightedPrior(prior, w));
      norm += w * w;
    }
  }

  public void build(final Posterior posterior, final float weight) {
    // Scale such that activation of the last principal component (may be
    // hypothetical, with relative weight 1) roughly has margins on either side of
    // the activation threshold (but cap the maximum at the default coefficient, and
    // never increase coefficients as a result of normalization).
    //
    // The actual formulation of the above, (norm - 1) / (1 - .5 / norm), doesn't
    // work as well in the presence of less significant components as the below, as
    // it produces false positives.
    final float normAdj = norm <= Prior.DEFAULT_COEFFICIENT ? 1
        : Math.max(norm / Prior.DEFAULT_COEFFICIENT, norm - .5f / norm);

    for (val weightedPrior : priors) {
      final float coefficient = weightedPrior.weight() / normAdj;
      assert coefficient <= Prior.DEFAULT_COEFFICIENT;
      weightedPrior.prior()
          .getPosteriors()
          .setCoefficient(posterior, coefficient, weight);
    }
  }
}
