package ai.xng.constructs;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;

import ai.xng.ActionCluster;
import ai.xng.IntegrationProfile;
import ai.xng.Posterior;
import ai.xng.PosteriorCluster;
import ai.xng.Scheduler;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
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

  /**
   * Avoids a lapsed-listener leak using a weak reference to the outer class.
   * There is no test covering this leak since it manifests only in transient
   * memory, the usage of which we do not have a great way to guage.
   * <p>
   * TODO: We might be able to do test this with Instrumentation.
   */
  private static class WeakSubscription<T extends Posterior> implements Observer<T> {
    final WeakReference<CoincidentEffect<T>> weakParent;
    Disposable subscription;

    WeakSubscription(final CoincidentEffect<T> parent) {
      weakParent = new WeakReference<>(parent);
    }

    @Override
    public void onSubscribe(final Disposable d) {
      subscription = d;
    }

    public void onNext(final T node) {
      val parent = weakParent.get();
      if (parent == null) {
        subscription.dispose();
      } else {
        val integrator = parent.node.getIntegrator();
        if (!integrator.isPending() && integrator.isActive()) {
          parent.apply(node);
        }
      }
    }

    @Override
    public void onComplete() {
    }

    @Override
    public void onError(Throwable e) {
    }
  }

  private void subscribe() {
    cluster.rxActivations().subscribe(new WeakSubscription<T>(this));
  }

  private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
    o.defaultReadObject();
    subscribe();
  }
}
