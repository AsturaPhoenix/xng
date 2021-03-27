package ai.xng;

import ai.xng.constructs.CoincidentEffect;
import lombok.val;

/**
 * This cluster contains input and output sub-clusters gated by an external
 * action node. The nomenclature of input and output are the reverse of input
 * and output elsewhere, as they refer to the input and output of this
 * bicluster.
 * <p>
 * Some of the nuances of the behavior of this class arise from the detail that
 * the activation status of a node arises from its integrator. Therefore, for
 * example, the active status of the gate node depends on its integrator, and
 * direct calls to the gate node's {@link Node#activate()} method will only
 * trigger passthrough of input nodes active at that instant. Any longer
 * activation duration must be driven synaptically. The inputs behave similarly.
 * <p>
 * Also, although the input/output connection behaves as though it were
 * conjuncted with the gate, it is not a true conjunction since that would scale
 * linearly with the number of nodes. Instead, the gate is queried during
 * propagation, and the recency queue is scanned on gate activation. This also
 * protects the output layer against gate summation.
 */
public class GatedBiCluster {
  public class InputCluster extends PosteriorCluster<InputCluster.Node> {
    private final DataCluster.FinalNode<InputCluster> clusterIdentifier;

    @Override
    public DataCluster.FinalNode<InputCluster> getClusterIdentifier() {
      return clusterIdentifier;
    }

    private InputCluster(final DataCluster identifierCluster) {
      clusterIdentifier = identifierCluster != null ? identifierCluster.new FinalNode<>(this) : null;
    }

    @Override
    public String toString() {
      return GatedBiCluster.this.comment != null ? GatedBiCluster.this.comment + " (input)" : super.toString();
    }

    public class Node extends OutputNode {
      private final Link link;

      public final OutputCluster.Node output;

      public Node() {
        this(null);
      }

      public Node(final String comment) {
        link = new Link(this);
        output = GatedBiCluster.this.output.new Node(comment);
      }

      @Override
      public InputCluster getCluster() {
        return InputCluster.this;
      }

      @Override
      public final void activate() {
        link.promote();
        super.activate();
      }

      @Override
      public String toString() {
        return GatedBiCluster.this + "/" + output.comment + " (input)";
      }
    }
  }

  public class OutputCluster extends PosteriorCluster<OutputCluster.Node> {
    private final DataCluster.FinalNode<OutputCluster> clusterIdentifier;

    @Override
    public DataCluster.FinalNode<OutputCluster> getClusterIdentifier() {
      return clusterIdentifier;
    }

    private OutputCluster(final DataCluster identifierCluster) {
      clusterIdentifier = identifierCluster != null ? identifierCluster.new FinalNode<>(this) : null;
    }

    @Override
    public String toString() {
      return GatedBiCluster.this.comment != null ? GatedBiCluster.this.comment + " (output)" : super.toString();
    }

    public class Node extends BiNode {
      private final Link link;
      private final String comment;

      private Node(final String comment) {
        link = new Link(this);
        this.comment = comment != null ? comment : Integer.toHexString(hashCode());
      }

      @Override
      public OutputCluster getCluster() {
        return OutputCluster.this;
      }

      @Override
      public final void activate() {
        link.promote();
        super.activate();
      }

      @Override
      public String toString() {
        return GatedBiCluster.this + "/" + comment + " (output)";
      }
    }
  }

  public final InputCluster input;
  public final OutputCluster output;
  public final ActionCluster.Node gate;

  private final String comment;

  public GatedBiCluster(final ActionCluster gateCluster) {
    this(gateCluster, null);
  }

  public GatedBiCluster(final ActionCluster gateCluster, final String comment) {
    val actionClusterAddress = gateCluster.getClusterIdentifier();
    val identifierCluster = actionClusterAddress != null ? actionClusterAddress.getCluster() : null;
    input = new InputCluster(identifierCluster);
    output = new OutputCluster(identifierCluster);

    gate = new CoincidentEffect<>(gateCluster, input) {
      @Override
      protected void apply(final InputCluster.Node node) {
        node.output.activate();
      }
    }.node;
    this.comment = comment;
  }

  @Override
  public String toString() {
    return comment != null ? comment : super.toString();
  }
}
