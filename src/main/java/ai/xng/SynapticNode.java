package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;

import lombok.val;

/**
 * A node that allows synaptic input.
 */
public class SynapticNode extends Node {
  private static final long serialVersionUID = -4340465118968553513L;

  public final Synapse synapse = new Synapse(this);

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
