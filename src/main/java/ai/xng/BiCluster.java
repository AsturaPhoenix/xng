package ai.xng;

public class BiCluster extends PriorCluster<BiNode> {
  private static final long serialVersionUID = 1L;

  public class Node extends BiNode {
    private static final long serialVersionUID = 1L;

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
