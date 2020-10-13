package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
      rxActivations.onNext(this);
      link.promote();
      super.activate();
    }
  }

  @RequiredArgsConstructor
  public class FinalNode extends Node {
    private static final long serialVersionUID = 1L;

    @Getter
    private final Serializable data;
  }

  public class MutableNode extends Node {
    private static final long serialVersionUID = 1L;

    private final DataNode.MaybeTransient container = new DataNode.MaybeTransient();

    @Override
    public Object getData() {
      return container.data;
    }

    public void setData(final Object value) {
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
