package ai.xng.constructs;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;

import ai.xng.ActionCluster;
import ai.xng.IntegrationProfile;
import ai.xng.Posterior;
import ai.xng.PosteriorCluster;
import ai.xng.Scheduler;
import io.reactivex.disposables.Disposable;
import lombok.val;

public class CoincidentEffects implements Serializable {
  public interface EffectFunction<T extends Posterior> extends Serializable, Consumer<T> {
  }

  public abstract class Effect<T extends Posterior> implements Serializable {
    private final PosteriorCluster<? extends T> cluster;
    private transient Disposable subscription;

    public Effect(final PosteriorCluster<? extends T> cluster) {
      this.cluster = cluster;
      effects.add(this);

      if (node.getIntegrator().isActive()) {
        applyPending();
      }
      subscribe();
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
          if (subscription != null && subscription.isDisposed()) {
            return;
          }
        }
      }
    }

    private void subscribe() {
      subscription = cluster.rxActivations().subscribe(node -> {
        val integrator = CoincidentEffects.this.node.getIntegrator();
        if (!integrator.isPending() && integrator.isActive()) {
          apply(node);
        }
      });
    }

    private void readObject(final ObjectInputStream o) throws ClassNotFoundException, IOException {
      o.defaultReadObject();
      subscribe();
    }

    public void remove() {
      effects.remove(this);
      subscription.dispose();
    }
  }

  public final ActionCluster.Node node;
  private final Collection<Effect<?>> effects = new ArrayList<>();

  public CoincidentEffects(final ActionCluster actionCluster) {
    node = actionCluster.new Node(this::onActivate);
  }

  private void onActivate() {
    for (val effect : effects) {
      effect.applyPending();
    }
  }

  public <T extends Posterior> CoincidentEffects add(final PosteriorCluster<? extends T> cluster,
      final EffectFunction<T> effect) {
    new Effect<T>(cluster) {
      @Override
      protected void apply(final T node) {
        effect.accept(node);
      }
    };

    return this;
  }

  public <T extends Posterior> CoincidentEffects addContingent(final PosteriorCluster<? extends T> cluster,
      final Posterior onNode, final EffectFunction<T> effect) {
    new Effect<T>(cluster) {
      @Override
      protected void apply(final T node) {
        if (onNode.getIntegrator().isActive()) {
          effect.accept(node);
        } else {
          remove();
        }
      }
    };

    return this;
  }
}
