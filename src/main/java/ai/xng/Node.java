package ai.xng;

import java.io.Serializable;
import java.util.Optional;

public interface Node extends Serializable {
  Cluster<?> getCluster();

  Optional<Long> getLastActivation();

  void activate();

  class Trait implements Serializable {
    private static final long serialVersionUID = 1L;

    private transient Long lastActivation;

    public Optional<Long> getLastActivation() {
      return Optional.ofNullable(lastActivation);
    }

    public void activate() {
      lastActivation = Scheduler.global.now();
    }
  }
}
