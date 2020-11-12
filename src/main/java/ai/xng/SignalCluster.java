package ai.xng;

public class SignalCluster extends PosteriorCluster<SignalCluster.Node> {
  private static final long serialVersionUID = 1L;

  public class Node extends OutputNode {
    private static final long serialVersionUID = 1L;

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
      publish(this);
    }
  }
}
