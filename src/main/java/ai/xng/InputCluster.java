package ai.xng;

public class InputCluster extends Cluster<InputCluster.Node> {
  private final DataCluster.FinalNode<InputCluster> clusterIdentifier;

  @Override
  public DataCluster.FinalNode<InputCluster> getClusterIdentifier() {
    return clusterIdentifier;
  }

  public InputCluster() {
    clusterIdentifier = null;
  }

  public InputCluster(final DataCluster identifierCluster) {
    clusterIdentifier = identifierCluster.new FinalNode<>(this);
  }

  public class Node extends InputNode {
    private final Link link;

    public Node() {
      link = new Link(this);
    }

    @Override
    public InputCluster getCluster() {
      return InputCluster.this;
    }

    @Override
    public void activate() {
      link.promote();
      super.activate();
    }
  }
}
