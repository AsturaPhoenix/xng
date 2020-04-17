package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

/**
 * A node that allows synaptic input.
 */
public class SynapticNode extends Node {
  private static final long serialVersionUID = -4340465118968553513L;

  public final Synapse synapse = new Synapse();

  public SynapticNode() {
    this(null);
  }

  public SynapticNode(final Serializable value) {
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
}
