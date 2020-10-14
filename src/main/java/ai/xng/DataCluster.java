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

    @Override
    public T getData() {
      return container.data;
    }

    public void setData(final T value) {
      container.data = value;
    }
  }

  public DataCluster() {
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
