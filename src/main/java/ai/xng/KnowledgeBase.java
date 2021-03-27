package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
      naming = new BiCluster("naming"),
      entrypoint = new BiCluster("entrypoint"),
      execution = new BiCluster("execution");
  public final ActionCluster actions = new ActionCluster(lastException);
  public final SignalCluster signals = new SignalCluster();
  public final GatedBiCluster gated = new GatedBiCluster(actions, "gated");

  public final SignalCluster.Node variadicEnd = signals.new Node();

  public final ActionCluster.Node print = actions.new Node(() -> data.rxActivations()
      .map(DataNode::getData)
      .firstElement()
      .subscribe(arg -> rxOutput.onNext(Objects.toString(arg)), lastException::setData)),
      findClass = actions.new Node(() -> data.rxActivations()
          .map(DataNode::getData)
          .firstElement()
          .subscribe(name -> returnValue.setData(Class.forName((String) name)))),
      getMethod = actions.new Node(() -> data.rxActivations()
          .map(DataNode::getData)
          .takeUntil(signals.rxActivations()
              .filter(signal -> signal == variadicEnd))
          .toList()
          .subscribe(
              args -> returnValue.setData(((Class<?>) args.get(0)).getMethod(
                  (String) args.get(1),
                  args.subList(2, args.size())
                      .toArray(Class<?>[]::new))),
              lastException::setData)),
      invokeMethod = actions.new Node(() -> data.rxActivations()
          .map(DataNode::getData)
          .take(2)
          .toList()
          .subscribe(args -> {
            var method = (Method) args.get(0);
            data.rxActivations()
                .map(DataNode::getData)
                .take(method.getParameterCount())
                .toList()
                .subscribe(
                    callArgs -> returnValue.setData(method.invoke(args.get(1), callArgs.toArray())),
                    lastException::setData);
          }, lastException::setData));

  public Observable<String> rxOutput() {
    return rxOutput;
  }

  private final Map<BiCluster, ActionCluster.Node> suppressPosteriors = new HashMap<>();

  public ActionCluster.Node suppressPosteriors(final BiCluster cluster) {
    return suppressPosteriors.computeIfAbsent(cluster, c -> new CoincidentEffect<BiNode>(actions, c) {
      @Override
      protected void apply(final BiNode node) {
        final float coincidence = node.getIntegrator().getNormalizedCappedValue();
        for (final Connections.Entry<Posterior> entry : node.getPosteriors()) {
          entry.edge().suppress(coincidence);
        }
      };
    }.node);
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
    return clearPosteriors.computeIfAbsent(cluster, c -> actions.new Node(() -> Cluster.disassociateAll(c)));
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
