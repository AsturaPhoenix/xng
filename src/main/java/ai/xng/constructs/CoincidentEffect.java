package ai.xng.constructs;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import ai.xng.ActionCluster;
import ai.xng.IntegrationProfile;
import ai.xng.Posterior;
import ai.xng.PosteriorCluster;
import ai.xng.Scheduler;
import lombok.val;

public abstract class CoincidentEffect<T extends Posterior> implements Serializable {
  public final ActionCluster.Node node;
  public final PosteriorCluster<? extends T> cluster;

  public CoincidentEffect(final ActionCluster actionCluster, final PosteriorCluster<? extends T> effectCluster) {
    node = actionCluster.new Node(this::onActivate);
    cluster = effectCluster;

    if (node.getIntegrator().isActive()) {
      applyPending();
    }
    subscribe();
  }

  private void onActivate() {
    applyPending();
  }

  protected abstract void apply(T node);

  private void applyPending() {
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

  private void subscribe() {
    cluster.rxActivations().subscribe(node -> {
      val integrator = CoincidentEffect.this.node.getIntegrator();
      if (!integrator.isPending() && integrator.isActive()) {
        apply(node);
      }
    });
  }

  private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
    o.defaultReadObject();
    subscribe();
  }
}
