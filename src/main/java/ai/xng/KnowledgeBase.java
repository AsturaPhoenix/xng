package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import ai.xng.Cluster.PriorClusterProfile;
import ai.xng.constructs.CoincidentEffect;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

public class KnowledgeBase implements Serializable, AutoCloseable {
  public static final float STACK_FACTOR = .5f, PUSH_FACTOR = STACK_FACTOR, POP_FACTOR = 1 / STACK_FACTOR;

  private transient Subject<String> rxOutput;

  public final InputCluster input = new InputCluster();
  public final DataCluster data = new DataCluster(input);
  public final DataCluster.MutableNode<String> inputValue = data.new MutableNode<>();
  public final DataCluster.MutableNode<Throwable> lastException = data.new MutableNode<>();
  public final DataCluster.MutableNode<Object> returnValue = data.new MutableNode<>();

  public final BiCluster stateRecognition = new BiCluster("stateRecognition"),
      sequenceRecognition = new BiCluster("sequenceRecognition"),
      context = new BiCluster("context"),
      naming = new BiCluster("naming"),
      entrypoint = new BiCluster("entrypoint"),
      execution = new BiCluster("execution");
  public final ActionCluster actions = new ActionCluster(lastException);
  public final SignalCluster signals = new SignalCluster();

  public final SignalCluster.Node variadicEnd = signals.new Node();

  public final ActionCluster.Node print = new CoincidentEffect.Lambda<>(actions, data,
      node -> rxOutput.onNext(Objects.toString(node.getData()))).node;

  public Observable<String> rxOutput() {
    return rxOutput;
  }

  private final Map<BiCluster, ActionCluster.Node> suppressPosteriors = new HashMap<>();

  public ActionCluster.Node suppressPosteriors(final BiCluster cluster) {
    return suppressPosteriors.computeIfAbsent(cluster, key -> new CoincidentEffect.Lambda<>(actions, key, node -> {
      final float coincidence = node.getIntegrator().getNormalizedCappedValue();
      for (final Connections.Entry<Posterior> entry : node.getPosteriors()) {
        entry.edge().suppress(coincidence);
      }
    }).node);
  }

  private static record ScalePosteriorsKey(Cluster<? extends Prior> cluster, float factor) implements Serializable {
  }

  private final Map<ScalePosteriorsKey, ActionCluster.Node> scalePosteriors = new HashMap<>();

  public ActionCluster.Node scalePosteriors(final Cluster<? extends Prior> cluster, final float factor) {
    return scalePosteriors.computeIfAbsent(new ScalePosteriorsKey(cluster, factor),
        key -> actions.new Node(() -> Cluster.scalePosteriors(key.cluster, key.factor)));
  }

  private final Map<Cluster<? extends Prior>, ActionCluster.Node> clearPosteriors = new HashMap<>();

  public ActionCluster.Node clearPosteriors(final Cluster<? extends Prior> cluster) {
    return clearPosteriors.computeIfAbsent(cluster, key -> actions.new Node(() -> Cluster.disassociateAll(key)));
  }

  private static record AssociateKey(Set<PriorClusterProfile> priors, PosteriorCluster<?> posteriorCluster)
      implements Serializable {
  }

  private final Map<AssociateKey, ActionCluster.Node> associate = new HashMap<>();

  public ActionCluster.Node associate(final Cluster<? extends Prior> priorCluster,
      final PosteriorCluster<?> posteriorCluster) {
    return associate(ImmutableSet.of(new PriorClusterProfile(priorCluster, IntegrationProfile.TRANSIENT)),
        posteriorCluster);
  }

  public ActionCluster.Node associate(final Iterable<PriorClusterProfile> priors,
      final PosteriorCluster<?> posteriorCluster) {
    return associate.computeIfAbsent(new AssociateKey(ImmutableSet.copyOf(priors), posteriorCluster),
        key -> actions.new Node(() -> Cluster.associate(key.priors, key.posteriorCluster)));
  }

  private static record DisassociateKey(Cluster<? extends Prior> priorCluster, PosteriorCluster<?> posteriorCluster)
      implements Serializable {
  }

  private final Map<DisassociateKey, ActionCluster.Node> disassociate = new HashMap<>();

  public ActionCluster.Node disassociate(final Cluster<? extends Prior> priorCluster,
      final PosteriorCluster<?> posteriorCluster) {
    return disassociate.computeIfAbsent(new DisassociateKey(priorCluster, posteriorCluster),
        key -> actions.new Node(() -> Cluster.disassociate(key.priorCluster, key.posteriorCluster)));
  }

  public KnowledgeBase() {
    init();
  }

  private void init() {
    rxOutput = PublishSubject.create();
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    stream.defaultReadObject();
    init();
  }

  @Override
  public void close() {
    rxOutput.onComplete();
  }
}
