package ai.xng;

import java.util.Optional;

import ai.xng.Connections.Priors;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@AllArgsConstructor
public class TestDataNode implements DataNode {
  private static final long serialVersionUID = 1L;

  @Getter
  public Object data;

  @Override
  public ThresholdIntegrator getIntegrator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public PosteriorCluster<?> getCluster() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Priors getPriors() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Integrator getTrace() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<Long> getLastActivation() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void activate() {
    throw new UnsupportedOperationException();
  }
}
