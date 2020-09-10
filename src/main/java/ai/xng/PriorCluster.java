package ai.xng;

import java.util.HashSet;
import java.util.Set;

public class PriorCluster<T extends Prior> extends Cluster<T> {
  private static final long serialVersionUID = 1L;

  public final Set<Cluster<? extends Posterior>> posteriorClusters = new HashSet<>();

  public void reinforce(final float weight) {
  }
}
