package ai.xng.constructs;

import java.io.Serializable;

import ai.xng.ActionCluster;
import ai.xng.DataCluster;
import ai.xng.GatedBiCluster;
import ai.xng.IntegrationProfile;
import ai.xng.Scheduler;
import io.reactivex.disposables.Disposable;
import lombok.val;

/**
 * This is heavily inspired by {@link GatedBiCluster}.
 */
public abstract class Decoder implements Serializable {
  public final ActionCluster.Node node;
  private final DataCluster input;

  private Disposable subscription;

  public Decoder(final ActionCluster actionCluster, final DataCluster input) {
    node = actionCluster.new Node(this::onActivate);
    this.input = input;
  }

  protected abstract void decode(Object data);

  private void onActivate() {
    decodePending();
    if (subscription == null) {
      subscription = input.rxActivations().subscribe(node -> {
        val integrator = this.node.getIntegrator();
        if (!integrator.isPending() && integrator.isActive()) {
          decode(node.getData());
        } else {
          subscription.dispose();
          subscription = null;
        }
      });
    }
  }

  private void decodePending() {
    final long now = Scheduler.global.now();

    for (val recent : input.activations()) {
      if (recent.getIntegrator().isPending()) {
        // This case will be handled by the subscription.
        continue;
      }
      if (recent.getLastActivation().get() < now - IntegrationProfile.PERSISTENT.period()) {
        // This assumes that PERSISTENT is an upper bound on integration curve periods.
        break;
      }

      if (recent.getIntegrator().isActive()) {
        decode(recent.getData());
      }
    }
  }
}
