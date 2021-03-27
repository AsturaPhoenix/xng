package ai.xng;

public class InputCluster extends Cluster<InputCluster.Node> {
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
