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
