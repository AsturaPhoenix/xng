package io.tqi.asn;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.ReplaySubject;
import io.reactivex.subjects.Subject;
import io.tqi.asn.value.NodeIterator;

public class KnowledgeBase implements Serializable, AutoCloseable {
  private static final long serialVersionUID = 4850129606513054849L;

  private final ConcurrentMap<Serializable, Node> index = new ConcurrentHashMap<>();
  private final Set<Node> nodes = Collections.newSetFromMap(new ConcurrentHashMap<>());

  private final Node EXECUTE = getOrCreateNode("execute"), ARGUMENT = getOrCreateNode("argument"),
      CALLBACK = getOrCreateNode("callback");

  private final Subject<String> rxOutput = PublishSubject.create();
  private final Subject<Void> rxChange = PublishSubject.create();

  @Override
  public void close() {
    rxOutput.onComplete();
    rxChange.onComplete();
  }

  public Observable<String> rxOutput() {
    return rxOutput;
  }

  public Observable<Void> rxChange() {
    return rxChange;
  }

  public Node getOrCreateNode(final String identifier) {
    return getOrCreateNode(new Identifier(identifier));
  }

  public Node getOrCreateNode(final Identifier identifier) {
    return internalGetOrCreateNode(identifier, null);
  }

  public Node getOrCreateValueNode(final Serializable value) {
    return internalGetOrCreateNode(value, value);
  }

  private Node internalGetOrCreateNode(final Serializable label, final Serializable value) {
    return index.computeIfAbsent(label, x -> {
      final Node node = new Node(value);
      nodes.add(node);
      node.rxActivate().subscribe(t -> {
        onNodeActivate(node);
      });
      node.rxChange().subscribe(rxChange::onNext);
      rxChange.onNext(null);
      return node;
    });
  }

  public void invoke(final Node rawFn, final Node rawArg, final Node rawCallback) {
    final Observable<Node> resolvedFn = evaluate(rawFn), resolvedArg = evaluate(rawArg),
        resolvedCallback = evaluate(rawCallback);

    resolvedArg.subscribe(arg -> {
      resolvedFn.filter(fn -> fn != null).subscribe(fn -> {
        final Subject<Node> subject = ReplaySubject.create();
        // duality: property access
        if (arg != null) {
          final Node asProp = arg.getProperty(fn);
          if (asProp != null) {
            subject.onNext(asProp);
          }
        }

        // duality: subroutine invocation
        fn.setProperty(ARGUMENT, arg);
        setObservableCallback(fn, subject);
        fn.activate();

        resolvedCallback.filter(c -> c != null).subscribe(callback -> {
          subject.subscribe(result -> {
            callback.setProperty(ARGUMENT, result);
            callback.activate();
          });
        });
      });
    });
  }

  private void onNodeActivate(final Node node) {
    final Node rawFn = node.getProperty(EXECUTE);
    if (rawFn != null) {
      invoke(rawFn, node.getProperty(ARGUMENT), node.getProperty(CALLBACK));
    }
  }

  private Observable<Node> evaluate(final Node node) {
    if (node == null) {
      return Observable.just(null);
    }

    final Node fn = node.getProperty(EXECUTE);
    if (fn == null) {
      return Observable.just(node);
    } else {
      final Subject<Node> subject = ReplaySubject.create();
      setObservableCallback(node, subject);
      node.activate();
      return subject;
    }
  }

  private void setObservableCallback(final Node routine, final Subject<Node> subject) {
    final Node callback = new Node(null);
    callback.rxActivate().subscribe(t -> {
      evaluate(callback.getProperty(ARGUMENT)).subscribe(subject::onNext);
    });
    routine.setProperty(CALLBACK, callback);
  }

  public void registerBuiltIn(final String name, final Consumer<Node> impl) {
    registerBuiltIn(name, arg -> {
      impl.accept(arg);
      return null;
    });
  }

  public void registerBuiltIn(final String name, final UnaryOperator<Node> impl) {
    final Node node = getOrCreateNode(name);
    node.rxActivate().subscribe(t -> {
      final Node result = impl.apply(node.getProperty(ARGUMENT));
      final Node callback = node.getProperty(CALLBACK);
      if (callback != null) {
        callback.setProperty(ARGUMENT, result);
        callback.activate();
      }
    });
  }

  {
    registerBuiltIn("splitString", node -> {
      final Object arg = node.getValue();
      if (arg instanceof String) {
        // TODO(rosswang): thread-safe mutable
        final ArrayList<Node> split = ((String) arg).chars().mapToObj(c -> {
          // TODO(rosswang): standardize node types
          return getOrCreateValueNode(Character.valueOf((char) c));
        }).collect(Collectors.toCollection(ArrayList::new));
        return getOrCreateValueNode(split);
      }

      return null;
    });

    registerBuiltIn("windowedIterator", node -> {
      final Object arg = node.getValue();
      if (arg instanceof List) {
        // TODO(rosswang): standardize node types
        @SuppressWarnings("unchecked")
        final ArrayList<Node> backing = (ArrayList<Node>) arg;
        final NodeIterator iterator = new NodeIterator(backing);
        final Node iterNode = new Node(iterator);

        final Node forward = new Node(null), back = new Node(null);
        iterNode.setProperty(getOrCreateNode("forward"), forward);
        iterNode.setProperty(getOrCreateNode("back"), back);

        final Node atStartProp = getOrCreateNode("atStart"), atEndProp = getOrCreateNode("atEnd"),
            onMoveProp = getOrCreateNode("onMove");
        iterNode.setProperty(atStartProp, new Node(null));
        iterNode.setProperty(atEndProp, new Node(null));
        iterNode.setProperty(onMoveProp, new Node(null));

        // TODO(rosswang): revisit whether we should have stateful properties
        // too

        final Consumer<Node> update = current -> {
          // Note that the atEnd and atStart property activations also result in
          // the atEnd and atStart globally indexed nodes being called with the
          // iterator as an argument.
          if (!iterator.hasNext()) {
            iterNode.getProperty(atEndProp).activate();
          }
          if (!iterator.hasPrevious()) {
            iterNode.getProperty(atStartProp).activate();
          }
          final Node onMove = iterNode.getProperty(onMoveProp);
          onMove.setProperty(ARGUMENT, current);
          current.activate();
          onMove.activate();
        };

        forward.rxActivate().subscribe(t -> {
          update.accept(iterator.next());
        });

        back.rxActivate().subscribe(t -> {
          update.accept(iterator.previous());
        });

        return iterNode;
      }

      return null;
    });

    registerBuiltIn("activateTailNGrams", node -> {
      final Object arg = node.getValue();
      if (arg instanceof ArrayList) {
        @SuppressWarnings("unchecked")
        final ArrayList<Object> listArg = (ArrayList<Object>) arg;
        activateTailNGrams(listArg);
      } else if (arg instanceof Collection) {
        @SuppressWarnings("unchecked")
        final Collection<Object> collectionArg = (Collection<Object>) arg;
        activateTailNGrams(new ArrayList<>(collectionArg));
      }
    });

    registerBuiltIn("print", node -> {
      final Object arg = node.getValue();
      if (arg instanceof String) {
        rxOutput.onNext((String) arg);
      }
    });
  }

  public void activateTailNGrams(ArrayList<Object> sequence) {
    while (!sequence.isEmpty()) {
      getOrCreateValueNode(sequence).activate();
      sequence = new ArrayList<>(sequence.subList(1, sequence.size()));
    }
  }
}
