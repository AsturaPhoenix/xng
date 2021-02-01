package ai.xng;

/**
 * This is an analogue of {@link GatedBiCluster} with an inhibitor instead of a
 * gate. As with {@code GatedBiCluster}, this is not a true inhibitor but rather
 * a coded construct, and inhibition is based on the integrator of the inhibitor
 * so activating the inhibitor node directly has no effect.
 */
public class InhibitedBiCluster {
  public class InputCluster extends PosteriorCluster<InputCluster.Node> {
    public class Node extends OutputNode {
      private final Link link;

      public final OutputCluster.Node output;

      public Node() {
        link = new Link(this);
        output = InhibitedBiCluster.this.output.new Node();
      }

      @Override
      public InputCluster getCluster() {
        return InputCluster.this;
      }

      @Override
      public final void activate() {
        link.promote();
        super.activate();

        if (!inhibitor.getIntegrator().isActive()) {
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

  public final Posterior inhibitor;
  public final InputCluster input = new InputCluster();
  public final OutputCluster output = new OutputCluster();

  public InhibitedBiCluster(final Posterior inhibitor) {
    this.inhibitor = inhibitor;
  }
}
