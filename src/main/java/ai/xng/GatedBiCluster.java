package ai.xng;

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
    public class Node extends OutputNode {
      private final Link link;

      public final OutputCluster.Node output;

      public Node() {
        link = new Link(this);
        output = GatedBiCluster.this.output.new Node();
      }

      @Override
      public InputCluster getCluster() {
        return InputCluster.this;
      }

      @Override
      public final void activate() {
        link.promote();
        super.activate();

        val integrator = gate.getIntegrator();
        if (!integrator.isPending() && integrator.isActive()) {
          output.activate();
        }
      }
    }
  }

  public class OutputCluster extends PosteriorCluster<OutputCluster.Node> {
    public class Node extends BiNode {
      private final Link link;

      private Node() {
        link = new Link(this);
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
    }
  }

  public final ActionCluster.Node gate;
  public final InputCluster input = new InputCluster();
  public final OutputCluster output = new OutputCluster();

  public GatedBiCluster(final ActionCluster gateCluster) {
    gate = gateCluster.new Node(this::firePending);
  }

  private void firePending() {
    final long now = Scheduler.global.now();

    for (val recent : input.activations()) {
      if (recent.getIntegrator().isPending()) {
        // This case will be handled by the Node.activate override.
        continue;
      }
      if (recent.getLastActivation().get() < now - IntegrationProfile.PERSISTENT.period()) {
        // This assumes that PERSISTENT is an upper bound on integration curve periods.
        break;
      }

      if (recent.getIntegrator().isActive()) {
        recent.output.activate();
      }
    }
  }
}
