package ai.xng;

public class InputCluster extends Cluster<InputNode> {
  private static final long serialVersionUID = 1L;

  public class Node extends InputNode {
    private static final long serialVersionUID = 1L;

    private final RecencyQueue<?>.Link link;

    public Node() {
      link = activations.new Link(this);
    }

    @Override
    public void activate() {
      link.promote();
      super.activate();
    }
  }
}
