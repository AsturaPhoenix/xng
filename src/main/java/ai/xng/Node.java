package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Optional;

import lombok.Getter;

public interface Node extends Serializable {
  long TRACE_SAMPLE_TTL = IntegrationProfile.PERSISTENT.period() * 3;

  Cluster<?> getCluster();

  LazyIntegrator getTrace();

  Optional<Long> getLastActivation();

  void activate();

  class Trait implements Serializable {
    private static final long serialVersionUID = 1L;

    // Something to keep in mind that we might need:
    // http://www.scholarpedia.org/article/Spike-timing_dependent_plasticity#Triplet_rule_of_STDP
    // http://www.scholarpedia.org/article/Spike-timing_dependent_plasticity#Diversity_of_STDP
    @Getter
    private transient LazyIntegrator trace;

    @Getter
    private transient Optional<Long> lastActivation;

    public Trait() {
      init();
    }

    private void init() {
      trace = new LazyIntegrator();
      lastActivation = Optional.empty();
    }

    public void activate() {
      final long now = Scheduler.global.now();
      trace.evict(now - TRACE_SAMPLE_TTL);
      trace.add(now, 1);
      lastActivation = Optional.of(now);
    }

    private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
      o.defaultReadObject();
      init();
    }
  }
}
