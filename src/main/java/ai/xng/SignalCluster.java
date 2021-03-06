package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

public class SignalCluster extends PosteriorCluster<SignalCluster.Node> {
  private transient Subject<Node> rxActivations;

  public Observable<Node> rxActivations() {
    return rxActivations;
  }

  public SignalCluster() {
    init();
  }

  private void init() {
    rxActivations = PublishSubject.create();
  }

  private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
    o.defaultReadObject();
    init();
  }

  public class Node extends OutputNode {
    private final Link link;

    public Node() {
      link = new Link(this);
    }

    @Override
    public SignalCluster getCluster() {
      return SignalCluster.this;
    }

    @Override
    public final void activate() {
      link.promote();
      super.activate();
      rxActivations.onNext(this);
    }
  }
}
