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
  private transient Subject<String> rxOutput;

  public final InputCluster input = new InputCluster();

  public final DataCluster data = new DataCluster(input);
  public final DataCluster.MutableNode<String> inputValue = data.new MutableNode<>();
  public final DataCluster.MutableNode<Throwable> lastException = data.new MutableNode<>();
  public final DataCluster.MutableNode<Object> returnValue = data.new MutableNode<>();

  public final BiCluster recognition = new BiCluster(),
      execution = new BiCluster(),
      timing = new BiCluster();
  public final ActionCluster actions = new ActionCluster(lastException);
  public final SignalCluster signals = new SignalCluster();
  public final GatedBiCluster context = new GatedBiCluster(actions);

  public final SignalCluster.Node variadicEnd = signals.new Node();

  @SuppressWarnings("unchecked")
  public final ActionCluster.Node disassociate = actions.new Node(() -> data.rxActivations()
      .map(DataNode::getData)
      .take(2)
      .toList()
      .subscribe(
          args -> Cluster.disassociate(
              (Cluster<? extends Prior>) args.get(0),
              (Cluster<? extends Posterior>) args.get(1)),
          lastException::setData)),
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
