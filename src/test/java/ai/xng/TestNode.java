package ai.xng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TestNode implements Posterior {
  private static final long serialVersionUID = 1L;

  private final Posterior.Trait input = new Posterior.Trait(this);
  private final List<Long> activations = new ArrayList<>();

  @Override
  public PosteriorCluster<?> getCluster() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void activate() {
    // This implementation skips LTP since it has no cluster.
    activations.add(Scheduler.global.now());
  }

  @Override
  public ThresholdIntegrator getIntegrator() {
    return input.getIntegrator();
  }

  @Override
  public Map<Prior, Distribution> getPriors() {
    return input.getPriors();
  }

  public boolean didActivate() {
    return !activations.isEmpty();
  }

  @Override
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
