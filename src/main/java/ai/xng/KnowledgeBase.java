package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Objects;

import ai.xng.constructs.CoincidentEffects;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.val;

public class KnowledgeBase implements Serializable, AutoCloseable {
  public static final float STACK_FACTOR = .5f;

  private transient Subject<String> rxOutput;

  public final DataCluster data = new DataCluster(this::updateNodeFactory);

  private InputCluster.Node updateNodeFactory() {
    return input.new Node();
  }

  public final InputCluster input = new InputCluster(data);
  public final DataCluster.MutableNode<String> inputValue = data.new MutableNode<>();
  public final DataCluster.MutableNode<Throwable> lastException = data.new MutableNode<>();
  public final DataCluster.MutableNode<Object> returnValue = data.new MutableNode<>();

  public final BiCluster stateRecognition = new BiCluster(data),
      sequenceRecognition = new BiCluster(data),
      naming = new BiCluster(data),
      entrypoint = new BiCluster(data),
      execution = new BiCluster(data);
  public final ActionCluster actions = new ActionCluster(lastException);
  public final SignalCluster signals = new SignalCluster(data);
  public final GatedBiCluster gated = new GatedBiCluster(actions);

  public final SignalCluster.Node variadicEnd = signals.new Node();

  public final DataCluster.FinalNode<Float> pushFactor = data.new FinalNode<>(STACK_FACTOR),
      popFactor = data.new FinalNode<>(1 / STACK_FACTOR);

  public final ActionCluster.Node suppressPosteriors,
      scalePosteriors,
      print = actions.new Node(() -> data.rxActivations()
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

  public KnowledgeBase() {
    init();

    val suppressor = new CoincidentEffects(actions);
    suppressor.add(data,
        clusterIdentifier -> {
          if (clusterIdentifier.getData() instanceof BiCluster cluster) {
            suppressor.addContingent(cluster, clusterIdentifier, prior -> {
              final float coincidence = prior.getIntegrator().getNormalizedCappedValue();
              for (final Connections.Entry<Posterior> entry : prior.getPosteriors()) {
                entry.edge().suppress(coincidence);
              }
            });
          }
        });
    suppressPosteriors = suppressor.node;

    val scaler = new CoincidentEffects(actions);
    scaler.add(data,
        clusterIdentifier -> {
          if (clusterIdentifier.getData() instanceof Cluster<?>cluster) {
            suppressor.addContingent(data, clusterIdentifier, factorNode -> {
              if (factorNode.getData() instanceof Number factor) {
                Cluster.scalePosteriors((Cluster<? extends Prior>) cluster, factor.floatValue());
              }
            });
          }
        });
    scalePosteriors = scaler.node;
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
