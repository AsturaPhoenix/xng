package ai.xng;

public class ActionCluster extends PosteriorCluster<ActionCluster.Node> {
  public final DataCluster.MutableNode<? super Throwable> exceptionHandler;

  public ActionCluster() {
    this(null);
  }

  public ActionCluster(final DataCluster.MutableNode<? super Throwable> exceptionHandler) {
    this.exceptionHandler = exceptionHandler;
  }

  public class Node extends ActionNode {
    private final ClusterNodeTrait clusterNode;

    public Node(final Action onActivate) {
      super(onActivate);
      clusterNode = new ClusterNodeTrait(this);
    }

    @Override
    public ActionCluster getCluster() {
      return ActionCluster.this;
    }

    @Override
    public final void activate() {
      clusterNode.activate();
      try {
        super.activate();
      } catch (final RuntimeException e) {
        // TODO: Consider removing this when it becomes less useful, or adding a real
        // logger.
        System.err.println(e);
        if (exceptionHandler != null) {
          exceptionHandler.setData(e);
        } else {
          throw e;
        }
      }
    }
  }
}
