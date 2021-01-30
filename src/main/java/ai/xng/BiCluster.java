package ai.xng;

public class BiCluster extends PosteriorCluster<BiCluster.Node> {
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
