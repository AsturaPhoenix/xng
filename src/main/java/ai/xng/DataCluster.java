package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

public class DataCluster extends PosteriorCluster<DataCluster.Node> {
  private static final long serialVersionUID = 1L;

  private transient Subject<Node> rxActivations;

  public Observable<Node> rxActivations() {
    return rxActivations;
  }

  private final InputCluster updateCluster;

  public abstract class Node extends OutputNode implements DataNode {
    private static final long serialVersionUID = 1L;

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
      rxActivations.onNext(this);
    }
  }

  public class FinalNode<T> extends Node {
    private static final long serialVersionUID = 1L;

    private final SerializableOrProxy<T> container;

    @Override
    public Object getData() {
      return container.getData();
    }

    public FinalNode(final T value) {
      container = new SerializableOrProxy<>(value);
    }
  }

  public class MutableNode<T> extends Node {
    private static final long serialVersionUID = 1L;

    private final DataNode.MaybeTransient<T> container = new DataNode.MaybeTransient<>();

    /**
     * Node activated when {@link #setData(Object)} is called. This node is
     * activated whether or not the data was actually changed.
     */
    public final InputCluster.Node onUpdate = updateCluster.new Node();

    @Override
    public T getData() {
      return container.data;
    }

    public void setData(final T value) {
      container.data = value;
      onUpdate.activate();
    }
  }

  public DataCluster(final InputCluster updateCluster) {
    this.updateCluster = updateCluster;
    init();
  }

  private void init() {
    rxActivations = PublishSubject.create();
  }

  private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
    o.defaultReadObject();
    init();
  }
}
