package ai.xng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class TestNode extends ActionNode {
  private static final long serialVersionUID = 1L;

  private final List<Long> activations = new ArrayList<>();

  @Override
  public Cluster<?> getCluster() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void activate() {
    // This implementation skips LTP since it has no cluster.
    activations.add(Scheduler.global.now());
  }

  public boolean didActivate() {
    return !activations.isEmpty();
  }

  public Optional<Long> getLastActivation() {
    return activations.isEmpty() ? Optional.empty() : Optional.of(activations.get(activations.size() - 1));
  }

  public int getActivationCount() {
    return activations.size();
  }

  public List<Long> getActivations() {
    return Collections.unmodifiableList(activations);
  }

  public void reset() {
    activations.clear();
  }
}
