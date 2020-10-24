package ai.xng;

import java.io.Serializable;

public interface NodeFactory extends Serializable {
  Posterior createNode();
}
