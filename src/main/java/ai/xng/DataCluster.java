package ai.xng;

import java.util.function.Supplier;

public class DataCluster extends PosteriorCluster<DataCluster.Node> {
  private final Supplier<InputCluster.Node> updateNodeFactory;
  private final FinalNode<DataCluster> clusterIdentifier;

  @Override
  public FinalNode<DataCluster> getClusterIdentifier() {
    return clusterIdentifier;
  }

  public abstract class Node extends OutputNode implements DataNode {
    private final Link link = new Link(this);

    private Node() {
    }

    @Override
    public DataCluster getCluster() {
      return DataCluster.this;
    }

    @Override
    public final void activate() {
      link.promote();
      super.activate();
    }
  }

  public class FinalNode<T> extends Node {
    private final SerializableOrProxy<T> container;

    @Override
    public T getData() {
      return container.getData();
    }

    public FinalNode(final T value) {
      container = new SerializableOrProxy<>(value);
    }

    @Override
    public String toString() {
      return "final " + getData();
    }
  }

  public class MutableNode<T> extends Node {
    private final DataNode.MaybeTransient<T> container = new DataNode.MaybeTransient<>();

    /**
     * Node activated when {@link #setData(Object)} is called. This node is
     * activated whether or not the data was actually changed.
     * <p>
     * This node is activated directly, so rapid updates are subject to temporal
     * summation. Conjunctions and other posteriors sensitive to temporal summation
     * should buffer with an intermediary node. .
     */
    public final InputCluster.Node onUpdate = updateNodeFactory.get();

    @Override
    public T getData() {
      return container.data;
    }

    public void setData(final T value) {
      container.data = value;
      onUpdate.activate();
    }

    @Override
    public String toString() {
      return "mutable " + getData();
    }
  }

  public DataCluster(final Supplier<InputCluster.Node> updateNodeFactory) {
    this.updateNodeFactory = updateNodeFactory;
    clusterIdentifier = new FinalNode<>(this);
  }
}
