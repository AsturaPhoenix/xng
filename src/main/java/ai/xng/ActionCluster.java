package ai.xng;

import ai.xng.DataCluster.FinalNode;

public class ActionCluster extends PosteriorCluster<ActionCluster.Node> {
  private final DataCluster.FinalNode<ActionCluster> clusterIdentifier;
  public final DataCluster.MutableNode<? super Throwable> exceptionHandler;

  @Override
  public FinalNode<ActionCluster> getClusterIdentifier() {
    return clusterIdentifier;
  }

  public ActionCluster() {
    clusterIdentifier = null;
    exceptionHandler = null;
  }

  public ActionCluster(final DataCluster.MutableNode<? super Throwable> exceptionHandler) {
    clusterIdentifier = exceptionHandler.getCluster().new FinalNode<>(this);
    this.exceptionHandler = exceptionHandler;
  }

  public class Node extends ActionNode {
    private final Link link;

    public Node(final Action onActivate) {
      super(onActivate);
      link = new Link(this);
    }

    @Override
    public ActionCluster getCluster() {
      return ActionCluster.this;
    }

    @Override
    public final void activate() {
      link.promote();
      try {
        super.activate();
      } catch (final RuntimeException e) {
        if (exceptionHandler != null) {
          exceptionHandler.setData(e);
        } else {
          throw e;
        }
      }
    }
  }
}
