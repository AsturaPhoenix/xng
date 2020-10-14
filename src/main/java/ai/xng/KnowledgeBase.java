package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Objects;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

public class KnowledgeBase implements Serializable, AutoCloseable {
  private static final long serialVersionUID = 2L;

  private transient Subject<String> rxOutput;

  public final InputCluster input = new InputCluster();
  public final BiCluster recognition = new BiCluster(),
      execution = new BiCluster();
  public final ActionCluster actions = new ActionCluster();
  public final SignalCluster signals = new SignalCluster();
  public final GatedBiCluster context = new GatedBiCluster(actions);
  public final DataCluster data = new DataCluster();

  public final DataCluster.FinalNode<InputCluster> inputCluster = data.new FinalNode<>(input);
  public final DataCluster.FinalNode<BiCluster> recognitionCluster = data.new FinalNode<>(recognition),
      executionCluster = data.new FinalNode<>(execution);
  public final DataCluster.FinalNode<ActionCluster> actionCluster = data.new FinalNode<>(actions);
  public final DataCluster.FinalNode<SignalCluster> signalCluster = data.new FinalNode<>(signals);
  public final DataCluster.FinalNode<GatedBiCluster.InputCluster> contextInput = data.new FinalNode<>(context.input);
  public final DataCluster.FinalNode<GatedBiCluster.OutputCluster> contextOutput = data.new FinalNode<>(context.output);
  public final DataCluster.FinalNode<DataCluster> dataCluster = data.new FinalNode<>(data);

  public final InputCluster.Node inputUpdated = input.new Node(),
      exceptionCaught = input.new Node();
  public final DataCluster.MutableNode<String> inputValue = data.new MutableNode<>();
  public final DataCluster.MutableNode<Throwable> lastException = data.new MutableNode<>();
  public final DataCluster.MutableNode<Object> returnValue = data.new MutableNode<>();

  public void exceptionHandler(final Throwable t) {
    lastException.setData(t);
    exceptionCaught.activate();
  }

  public final SignalCluster.Node variadicEnd = signals.new Node();

  @SuppressWarnings("unchecked")
  public final ActionCluster.Node associate = actions.new Node(
      () -> data.rxActivations()
          .map(DataNode::getData)
          .buffer(2)
          .firstElement()
          .subscribe(
              args -> Cluster.associate(
                  (Cluster<? extends Prior>) args.get(0),
                  (Cluster<? extends Posterior>) args.get(1)),
              this::exceptionHandler)),
      disassociate = actions.new Node(
          () -> data.rxActivations()
              .map(DataNode::getData)
              .buffer(2)
              .firstElement()
              .subscribe(
                  args -> Cluster.associate(
                      (Cluster<? extends Prior>) args.get(0),
                      (Cluster<? extends Posterior>) args.get(1)),
                  this::exceptionHandler)),
      print = actions.new Node(() -> data.rxActivations()
          .map(DataNode::getData)
          .firstElement()
          .subscribe(arg -> rxOutput.onNext(Objects.toString(arg)), this::exceptionHandler)),
      findClass = actions.new Node(() -> data.rxActivations()
          .map(DataNode::getData)
          .firstElement()
          .subscribe(name -> returnValue.setData(Class.forName((String) name)))),
      getMethod = actions.new Node(() -> data.rxActivations()
          .map(DataNode::getData)
          .buffer(signals.rxActivations()
              .filter(signal -> signal == variadicEnd))
          .firstElement()
          .subscribe(
              args -> returnValue.setData(((Class<?>) args.get(0)).getMethod(
                  (String) args.get(1),
                  args.subList(2, args.size())
                      .toArray(Class<?>[]::new))),
              this::exceptionHandler)),
      invokeMethod = actions.new Node(() -> data.rxActivations()
          .map(DataNode::getData)
          .buffer(2)
          .firstElement()
          .subscribe(args -> {
            var method = (Method) args.get(0);
            data.rxActivations()
                .map(DataNode::getData)
                .buffer(method.getParameterCount())
                .firstElement()
                .subscribe(
                    callArgs -> returnValue.setData(method.invoke(args.get(1), callArgs.toArray())),
                    this::exceptionHandler);
          }, this::exceptionHandler));

  public Observable<String> rxOutput() {
    return rxOutput;
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
