package ai.xng;

import lombok.val;

public class BiCluster extends PosteriorCluster<BiCluster.Node> {
  private final String comment;

  public BiCluster() {
    this(null);
  }

  public BiCluster(final String comment) {
    this.comment = comment;
  }

  public class Node extends BiNode {
    private final Link link;
    private final String comment;

    public Node() {
      this(null);
    }

    public Node(final String comment) {
      link = new Link(this);
      this.comment = comment;
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

    @Override
    public String toString() {
      if (BiCluster.this.comment == null) {
        return super.toString();
      }

      val sb = new StringBuilder(BiCluster.this.comment).append('/');
      sb.append(comment != null ? comment : Integer.toHexString(hashCode()));
      return sb.toString();
    }
  }

  @Override
  public String toString() {
    return comment != null ? comment : super.toString();
  }
}
