package ai.xng;

public class InputCluster extends Cluster<InputCluster.Node> {
  public class Node extends InputNode {
    private final ClusterNodeTrait clusterNode;

    public Node() {
      clusterNode = new ClusterNodeTrait(this);
    }

    @Override
    public InputCluster getCluster() {
      return InputCluster.this;
    }

    @Override
    public void activate() {
      clusterNode.activate();
      super.activate();
    }
  }
}
