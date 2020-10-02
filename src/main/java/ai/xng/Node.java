package ai.xng;

import java.io.Serializable;

public interface Node extends Serializable {
  Cluster<?> getCluster();

  void activate();
}
