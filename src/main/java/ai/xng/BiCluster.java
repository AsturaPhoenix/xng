package ai.xng;

public class BiCluster extends PosteriorCluster<BiCluster.Node> {
  private final DataCluster.FinalNode<BiCluster> clusterIdentifier;

  @Override
  public DataCluster.FinalNode<BiCluster> getClusterIdentifier() {
    return clusterIdentifier;
  }

  public BiCluster() {
    clusterIdentifier = null;
  }

  public BiCluster(final DataCluster identifierCluster) {
    clusterIdentifier = identifierCluster.new FinalNode<>(this);
  }

  public class Node extends BiNode {
    private final Link link;

    public Node() {
      link = new Link(this);
    }

    @Override
    public BiCluster getCluster() {
      return BiCluster.this;
    }

    @Override
    public void activate() {
      link.promote();
      super.activate();
    }
  }
}
