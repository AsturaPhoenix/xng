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

  public void conjunction(final Node... priors) {
    final float weight = (Synapse.THRESHOLD - THRESHOLD_MARGIN) / (priors.length - 1);
    if (weight * priors.length < Synapse.THRESHOLD) {
      throw new IllegalArgumentException(
          "Too many priors to guarantee reliable conjunction. Recommend staging the evaluation into a tree.");
    }

    for (val prior : priors) {
      synapse.setCoefficient(prior, weight);
    }
  }
}
