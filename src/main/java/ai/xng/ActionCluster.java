package ai.xng;

public class ActionCluster extends Cluster<ActionNode> {
  private static final long serialVersionUID = 1L;

  public class Node extends ActionNode.Lambda {
    private static final long serialVersionUID = 1L;

    private final Link link;

    public Node(final OnActivate onActivate) {
      super(onActivate);
      link = new Link(this);
    }

    @Override
    public ActionCluster getCluster() {
      return ActionCluster.this;
    }

    @Override
    public final void activate() {
      super.activate();
      link.promote();
    }
  }
}
