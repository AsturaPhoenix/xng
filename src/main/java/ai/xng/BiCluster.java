package ai.xng;

import lombok.val;

public class BiCluster extends PosteriorCluster<BiCluster.Node> {
  private final DataCluster.FinalNode<BiCluster> clusterIdentifier;
  private final String comment;

  @Override
  public DataCluster.FinalNode<BiCluster> getClusterIdentifier() {
    return clusterIdentifier;
  }

  public BiCluster() {
    clusterIdentifier = null;
    comment = null;
  }

  public BiCluster(final DataCluster identifierCluster, final String comment) {
    clusterIdentifier = identifierCluster.new FinalNode<>(this);
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
