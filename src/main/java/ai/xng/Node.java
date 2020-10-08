package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Optional;

import lombok.Getter;

public interface Node extends Serializable {
  long TRACE_RAMP_UP = Prior.RAMP_UP, TRACE_RAMP_DOWN = Prior.RAMP_DOWN,
      TRACE_TTL = 2 * TRACE_RAMP_UP + TRACE_RAMP_DOWN;

  Cluster<?> getCluster();

  Integrator getTrace();

  Optional<Long> getLastActivation();

  void activate();

  class Trait implements Serializable {
    private static final long serialVersionUID = 1L;

    @Getter
    private transient Integrator trace;

    @Getter
    private transient Optional<Long> lastActivation;

    public Trait() {
      init();
    }

    private void init() {
      trace = new Integrator();
      lastActivation = Optional.empty();
    }

    public void activate() {
      final long now = Scheduler.global.now();
      trace.evict(now - TRACE_TTL);
      trace.add(now, TRACE_RAMP_UP, TRACE_RAMP_DOWN, 1);
      lastActivation = Optional.of(now);
    }

    private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
      o.defaultReadObject();
      init();
    }
  }
}
