package ai.xng;

public class SignalCluster extends PosteriorCluster<SignalCluster.Node> {
  private final DataCluster.FinalNode<SignalCluster> clusterIdentifier;

  @Override
  public DataCluster.FinalNode<SignalCluster> getClusterIdentifier() {
    return clusterIdentifier;
  }

  public SignalCluster() {
    clusterIdentifier = null;
  }

  public SignalCluster(final DataCluster identifierCluster) {
    clusterIdentifier = identifierCluster.new FinalNode<>(this);
  }

  public class Node extends OutputNode {
    private final Link link;

    public Node() {
      link = new Link(this);
    }

    @Override
    public SignalCluster getCluster() {
      return SignalCluster.this;
    }

    @Override
    public final void activate() {
      link.promote();
      super.activate();
    }
  }
}
