package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;

import lombok.Getter;
import lombok.val;

/**
 * A node that keeps track of its synapse.
 * <p>
 * The intrinsic lock is used during synapse reparenting operations, so client
 * code that requires related consistency should hold the lock.
 */
public class SynapticNode extends Node {
  private static final long serialVersionUID = -4340465118968553513L;

  @Getter
  private Synapse synapse = new Synapse(this);

  /**
   * Splits this node into a prior and posterior, where {@code this} becomes the
   * posterior and the prior is returned.
   */
  public synchronized SynapticNode split() {
    synchronized (synapse) {
      val prior = new SynapticNode();
      val oldSynapse = synapse;

      synapse = prior.synapse;
      prior.synapse = oldSynapse;

      synapse.setNode(this);
      oldSynapse.setNode(prior);

      prior.then(this);
      return prior;
    }
  }

  public SynapticNode() {
    this(null);
  }

  public SynapticNode(final Object value) {
    super(value);
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    stream.defaultReadObject();
  }

  public SynapticNode conjunction(final Node... priors) {
    final float weight = (Synapse.THRESHOLD - THRESHOLD_MARGIN) / (priors.length - 1);
    if (weight * priors.length < Synapse.THRESHOLD + THRESHOLD_MARGIN) {
      throw new IllegalArgumentException(
          "Too many priors to guarantee reliable conjunction. Recommend staging the evaluation into a tree.");
    }

    for (val prior : priors) {
      synapse.setCoefficient(prior, weight);
    }

    return this;
  }

  public SynapticNode disjunction(final Node... priors) {
    for (val prior : priors) {
      prior.then(this);
    }

    return this;
  }

  public SynapticNode inhibitor(final Node antiprior) {
    synapse.setCoefficient(antiprior, -1);
    return this;
  }
}
