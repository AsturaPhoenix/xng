package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;

import lombok.val;

/**
 * A node that allows synaptic input.
 */
public class SynapticNode extends Node {
  private static final long serialVersionUID = -4340465118968553513L;

  /**
   * Maximum allowed activation resulting from an incomplete set of priors from a
   * conjunction.
   */
  public static final float CONJUNCTION_MARGIN = .2f;

  public final Synapse synapse = new Synapse();

  public SynapticNode() {
    this(null);
  }

  public SynapticNode(final Object value) {
    super(value);
    init();
  }

  private void init() {
    synapse.rxActivate().subscribe(a -> activate(a.context));
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    stream.defaultReadObject();
    init();
  }

  public void conjunction(final Node... priors) {
    final float weight = (1 - CONJUNCTION_MARGIN) / (priors.length - 1);
    for (val prior : priors) {
      synapse.setCoefficient(prior, weight);
    }
  }
}
