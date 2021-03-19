package ai.xng.constructs;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import ai.xng.ActionCluster;
import ai.xng.Cluster;
import ai.xng.IntegrationProfile;
import ai.xng.Posterior;
import ai.xng.PosteriorCluster;
import ai.xng.Scheduler;
import io.reactivex.disposables.Disposable;
import lombok.val;

public abstract class CoincidentEffect<T extends Posterior> implements Serializable {
  public final ActionCluster.Node node;
  private transient Map<PosteriorCluster<? extends T>, Disposable> subscriptions = new HashMap<>();

  public CoincidentEffect(final ActionCluster actionCluster) {
    node = actionCluster.new Node(this::onActivate);
    init();
  }

  private void init() {
    subscriptions = new HashMap<>();
  }

  protected abstract void apply(T node);

  private Disposable subscribe(final PosteriorCluster<? extends T> cluster) {
    return cluster.rxActivations().subscribe(node -> {
      val integrator = this.node.getIntegrator();
      if (!integrator.isPending() && integrator.isActive()) {
        apply(node);
      }
    });
  }

  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.defaultWriteObject();
    o.writeObject(subscriptions.keySet().toArray());
  }

  private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
    o.defaultReadObject();
    init();
    for (val e : (Object[]) o.readObject()) {
      val cluster = (PosteriorCluster<? extends T>) e;
      subscriptions.put(cluster, subscribe(cluster));
    }
  }

  public CoincidentEffect<T> addCluster(final PosteriorCluster<? extends T> cluster) {
    subscriptions.computeIfAbsent(cluster, c -> {
      if (node.getIntegrator().isActive()) {
        applyPending(c);
      }

      return subscribe(c);
    });

    return this;
  }

  public void removeCluster(final PosteriorCluster<? extends T> cluster) {
    subscriptions.remove(cluster).dispose();
  }

  private void onActivate() {
    for (val entry : subscriptions.entrySet()) {
      applyPending(entry.getKey());
    }
  }

  private void applyPending(final Cluster<? extends T> cluster) {
    for (val recent : cluster.activations()) {
      if (recent.getIntegrator().isPending()) {
        // This case will be handled by the subscription.
        continue;
      }
      if (recent.getLastActivation().get() < Scheduler.global.now() - IntegrationProfile.PERSISTENT.period()) {
        // This assumes that PERSISTENT is an upper bound on integration curve periods.
        break;
      }

      if (recent.getIntegrator().isActive()) {
        apply(recent);
      }
    }
  }
}
