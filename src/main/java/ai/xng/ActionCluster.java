package ai.xng;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ActionCluster extends PosteriorCluster<ActionCluster.Node> {
  private static final long serialVersionUID = 1L;

  public final DataCluster.MutableNode<? super Throwable> exceptionHandler;

  public ActionCluster() {
    this(null);
  }

  public class Node extends ActionNode {
    private static final long serialVersionUID = 1L;

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
