package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.RequiredArgsConstructor;
import lombok.Value;

public class KnowledgeBase implements Serializable, AutoCloseable, Iterable<Node> {
  private static final long serialVersionUID = -6461427806563494150L;

  @RequiredArgsConstructor
  private static class IdentityKey implements Serializable {
    private static final long serialVersionUID = -2428169144581856842L;

    final Serializable value;

    @Override
    public int hashCode() {
      return System.identityHashCode(value);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof IdentityKey && value == ((IdentityKey) obj).value;
    }
  }

  private ConcurrentMap<IdentityKey, Node> valueIndex = new ConcurrentHashMap<>();

  private transient NodeQueue nodes = new NodeQueue();

  private transient Subject<String> rxOutput;
  private transient Subject<Node> rxNodeAdded;

  public enum Common {
    // invocation
    invoke, literal, transform, exceptionHandler,

    javaClass, object, name, exception, source, value, refractory, nodeCreated, nullNode
  }

  public enum BuiltIn {
    clearProperties {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) {
        context.require(kb.node(Common.object)).properties.clear();
        return null;
      }
    },
    /**
     * Gets a static or instance Java field.
     */
    field {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) throws Exception {
        final Node classNode = context.index.get(kb.node(Common.javaClass)),
            objectNode = context.index.get(kb.node(Common.object)),
            fieldNode = context.require(kb.node(Common.name));
        final Object object;
        final Class<?> clazz;

        if (objectNode != null) {
          object = objectNode.getValue();

          if (classNode != null) {
            clazz = (Class<?>) classNode.getValue();
            if (!clazz.isAssignableFrom(object.getClass())) {
              throw new IllegalArgumentException("Provided class does not match object class");
            }
          } else {
            clazz = object.getClass();
          }
        } else {
          object = null;
          clazz = (Class<?>) classNode.getValue();
        }

        Field field = clazz.getField((String) fieldNode.getValue());

        Object ret = field.get(object);
        return kb.node((Serializable) ret);
      }
    },
    /**
     * Takes two args: {@link Common#object} and {@link Common#name}. If {@code object} is omitted,
     * gets the property from the parent context.
     */
    getProperty {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) {
        final Node object = context.index.get(kb.node(Common.object)),
            property = context.require(kb.node(Common.name));
        return object == null ? context.parent.index.get(property)
            : object.properties.get(property);
      }
    },
    /**
     * A limited Java interop bridge. Method parameter types must be fully and exactly specified via
     * "paramN" index entries, null-terminated, having values of Java classes. Arguments are passed
     * as "argN" index entries, and must be serializable. Missing arguments are passed null.
     * 
     * {@link Common#javaClass}: optional class on which to invoke method. If {@link Common#object}
     * is also provided, this must be a supertype. {@link Common#object}: optional object on which
     * to invoke method. {@link Common#name}: required name of the method to invoke.
     */
    method {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) throws Exception {
        final Node classNode = context.index.get(kb.node(Common.javaClass)),
            objectNode = context.index.get(kb.node(Common.object)),
            methodNode = context.require(kb.node(Common.name));
        final Object object;
        final Class<?> clazz;

        if (objectNode != null) {
          object = objectNode.getValue();

          if (classNode != null) {
            clazz = (Class<?>) classNode.getValue();
            if (!clazz.isAssignableFrom(object.getClass())) {
              throw new IllegalArgumentException("Provided class does not match object class");
            }
          } else {
            clazz = object.getClass();
          }
        } else {
          object = null;
          clazz = (Class<?>) classNode.getValue();
        }

        ArrayList<Class<?>> params = new ArrayList<>();
        Node param;
        for (int i = 1; (param = context.index.get(kb.param(i))) != null; i++) {
          params.add((Class<?>) param.getValue());
        }

        Method method =
            clazz.getMethod((String) methodNode.getValue(), params.toArray(new Class<?>[0]));
        ArrayList<Object> args = new ArrayList<>();
        for (int i = 1; i <= params.size(); i++) {
          Node arg = context.index.get(kb.arg(i));
          args.add(arg == null ? null : arg.getValue());
        }

        Object ret = method.invoke(object, args.toArray());
        return method.getReturnType() == null ? null : kb.node((Serializable) ret);
      }
    },
    findClass {
      @Override
      public Node impl(final KnowledgeBase kb, Context context) throws ClassNotFoundException {
        return kb.node(Class.forName((String) context.require(kb.node(Common.name)).getValue()));
      }
    },
    createNode {
      @Override
      public Node impl(final KnowledgeBase kb, Context context) {
        return kb.node();
      }
    },
    print {
      @Override
      public Node impl(final KnowledgeBase kb, Context context) {
        kb.rxOutput.onNext(Objects.toString(context.require(kb.node(Common.value)).getValue()));
        return null;
      }
    },
    /**
     * <li>{@link Common#object}: optional node on which to set a property. If omitted, sets the
     * property on the parent context.
     * <li>{@link Common#name}: required property name.
     * <li>{@link Common#value}: optional value to set. If null, the property is cleared.
     * <li>Returns the value previously at this property.
     */
    setProperty {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) {
        final Node object = context.index.get(kb.node(Common.object)),
            property = context.require(kb.node(Common.name)),
            value = context.index.get(kb.node(Common.value));
        if (object == null) {
          if (value == null) {
            return context.parent.index.remove(property);
          } else {
            return context.parent.index.put(property, value);
          }
        } else {
          if (value == null) {
            return object.properties.remove(property);
          } else {
            return object.properties.put(property, value);
          }
        }
      }
    },
    setRefractory {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) {
        context.require(kb.node(Common.value)).setRefractory(
            ((Number) context.require(kb.node(Common.refractory)).getValue()).longValue());
        return null;
      }
    },
    /**
     * Closes the parent context with the given return value.
     * 
     * {@link Common#value}: optional return value.
     */
    contextReturn {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) {
        final Node value = context.index.get(kb.node(Common.value));
        context.parent.close(value);
        return null;
      }
    };

    public abstract Node impl(KnowledgeBase kb, Context context) throws Exception;
  }

  public KnowledgeBase() {
    init();
    bootstrap();
  }

  private void init() {
    rxOutput = PublishSubject.create();

    for (final Node node : nodes) {
      initNode(node);
    }

    // create any new Common nodes
    for (final Common common : Common.values()) {
      node(common);
    }

    for (final BuiltIn builtIn : BuiltIn.values()) {
      registerBuiltIn(builtIn);
    }

    rxNodeAdded = PublishSubject.create();
  }

  private void initNode(final Node node) {
    node.setOnActivate(context -> maybeInvoke(node, context));

    if (rxNodeAdded != null) {
      rxNodeAdded.onNext(node);
    }
  }

  /**
   * Invocation activates the node specified by {@link Common#invoke} with a child context
   * constructed from {@link Common#literal} and {@link Common#transform}. Literal properties are
   * assigned directly to the new context, and transform properties are copied from the parent
   * context entry named by the value of the transform property. Transform properties take
   * precedence over literal properties if present, allowing literals to act as defaults. The return
   * value, if any, is assigned to the context property named by the node with the invocation, whose
   * activation triggered the invocation.
   * 
   * @param node
   * @param context
   */
  private void maybeInvoke(final Node node, final Context context) {
    final Node invoke = node.properties.get(node(Common.invoke));
    if (invoke == null)
      return;

    final Context childContext = new Context(context);
    final Node literal = node.properties.get(node(Common.literal));
    if (literal != null) {
      synchronized (literal.properties) {
        for (final Entry<Node, Node> mapping : literal.properties.entrySet()) {
          childContext.index.put(mapping.getKey(), mapping.getValue());
        }
      }
    }

    final Node transform = node.properties.get(node(Common.transform));
    if (transform != null) {
      synchronized (transform.properties) {
        for (final Entry<Node, Node> mapping : transform.properties.entrySet()) {
          final Node value = context.index.get(mapping.getValue());
          if (value != null) {
            childContext.index.put(mapping.getKey(), value);
          }
        }
      }
    }

    invoke.activate(childContext);

    final Node retval;
    try {
      retval = childContext.lifetime().get();
    } catch (final ExecutionException e) {
      final Node exceptionHandler = node.properties.get(node(Common.exceptionHandler));
      if (exceptionHandler != null) {
        final Node exceptionNode = node(new ImmutableException(e.getCause()));
        exceptionNode.properties.put(node(Common.source), node);
        context.index.put(node(Common.exception), exceptionNode);

        exceptionHandler.activate(context);
      } else {
        // TODO(rosswang): preserve nodespace stack trace
        context.close(e.getCause());
      }
      return;
    } catch (final InterruptedException e) {
      throw new RuntimeException(e);
    }

    if (retval != null) {
      context.index.put(node, retval);
    }
  }

  private Node registerBuiltIn(final BuiltIn builtIn) {
    // TODO(rosswang): Keep the original built-in impl node indexed separate
    // from the main index so that we can rebind the impl to the correct
    // node after deserialization.
    final Node node = node(builtIn);
    node.setOnActivate(context -> {
      final Node retval;
      try {
        retval = builtIn.impl(this, context);
      } catch (final Exception e) {
        context.close(e);
        return;
      }
      context.close(retval);
    });
    return node;
  }

  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.defaultWriteObject();
    final List<Node> serNodes = new ArrayList<>();
    synchronized (nodes.mutex()) {
      for (final Node node : nodes) {
        serNodes.add(node);
      }
    }
    o.writeObject(serNodes);
  }

  @SuppressWarnings("unchecked")
  private void readObject(final ObjectInputStream stream)
      throws ClassNotFoundException, IOException {
    stream.defaultReadObject();
    nodes = new NodeQueue();
    nodes.addAll((List<Node>) stream.readObject());

    init();
  }

  @Override
  public void close() {
    rxOutput.onComplete();
    rxNodeAdded.onComplete();
  }

  public Observable<Node> rxNodeAdded() {
    return rxNodeAdded;
  }

  public Observable<String> rxOutput() {
    return rxOutput;
  }

  @Value
  private class Arg implements Serializable {
    private static final long serialVersionUID = 1L;
    int ordinal;
  }

  @Value
  private class Param implements Serializable {
    private static final long serialVersionUID = 1L;
    int ordinal;
  }

  /**
   * Gets or creates a node representing a positional argument. These are typically property names
   * under {@code ARGUMENT} nodes.
   * 
   * @param ordinal one-based argument index
   * @return "arg<i>n</i>"
   */
  public Node arg(final int ordinal) {
    return node(new Arg(ordinal));
  }

  /**
   * Gets or creates a node representing a positional parameter type, for use with
   * {@link BuiltIn#invoke} as property names under {@code ARGUMENT} nodes.
   * 
   * @param ordinal one-based argument index
   * @return "param<i>n</i>"
   */
  public Node param(final int ordinal) {
    return node(new Param(ordinal));
  }

  @SuppressWarnings("unchecked")
  public Node node(final Serializable value) {
    // Single-instance resolution for immutable types. Skip for/bootstrap
    // with Class and enum since they're always single-instance.
    //
    // An immutable class is designated by having the node for the class
    // instance have a Common.value property that has a value of a
    // ConcurrentHashMap, which will be used to resolve values to canonical
    // instances.
    Serializable resolvingValue = value;
    if (!(value instanceof Class<?> || value instanceof Enum)) {
      final Node values = node(value.getClass()).properties.get(node(Common.value));
      if (values != null && values.getValue() instanceof ConcurrentHashMap) {
        resolvingValue = ((ConcurrentHashMap<Serializable, Serializable>) values.getValue())
            .computeIfAbsent(value, x -> value);
      }
    }

    final Serializable resolvedValue = resolvingValue;

    return valueIndex.computeIfAbsent(new IdentityKey(resolvedValue), x -> {
      final Node node = new Node(resolvedValue);
      initNode(node);
      nodes.add(node);
      return node;
    });
  }

  // All kb nodes must be created through a node(...) method to ensure the
  // proper callbacks are set.
  public Node node() {
    final Node node = new Node();
    initNode(node);
    nodes.add(node);
    return node;
  }

  @Override
  public Iterator<Node> iterator() {
    return nodes.iterator();
  }

  public Observable<Node> rxActivate() {
    return nodes.rxActivate();
  }

  public Object iteratorMutex() {
    return nodes.mutex();
  }

  public enum Bootstrap {
    /**
     * <li>{@link Common#javaClass}: class to mark immutable
     */
    markImmutable,
    /**
     * <li>{@link Common#javaClass}: class for which to invoke default constructor.
     * <li>Returns the instance that was created.
     */
    newInstance,
    /**
     * <li>{@link Common#value}: string to evaluate
     */
    eval
  }

  public class Invocation {
    public final Node node;

    public Invocation(final Node node, final Node invoke) {
      this.node = node;
      node.properties.put(node(Common.invoke), invoke);
    }

    public Invocation literal(final Node key, final Node value) {
      node.properties.computeIfAbsent(node(Common.literal), k -> node()).properties.put(key, value);
      return this;
    }

    public Invocation transform(final Node key, final Node value) {
      node.properties.computeIfAbsent(node(Common.transform), k -> node()).properties.put(key,
          value);
      return this;
    }

    public Invocation exceptionHandler(final Node invoke) {
      node.properties.put(node(Common.exceptionHandler), invoke);
      return this;
    }
  }

  // Creates common support routines and marks basic immutable types.
  private void bootstrap() {
    // Since strings are used for reflection at all and markImmutable is
    // implemented in terms of reflection, we need to bootstrap strings as
    // immutable.
    node(String.class).properties.put(node(Common.value),
        node(new ConcurrentHashMap<Serializable, Serializable>()));

    final Node markImmutable = node(Bootstrap.markImmutable),
        newInstance = node(Bootstrap.newInstance), eval = node(Bootstrap.eval);

    {
      new Invocation(markImmutable, newInstance).literal(node(Common.javaClass),
          node(ConcurrentHashMap.class));

      new Invocation(markImmutable.then(node()), node(BuiltIn.setProperty))
          .literal(node(Common.name), node(Common.value))
          .transform(node(Common.object), node(Common.javaClass))
          .transform(node(Common.value), markImmutable);
    }

    {
      new Invocation(newInstance, node(BuiltIn.method))
          .literal(node(Common.name), node("newInstance"))
          .transform(node(Common.object), node(Common.javaClass));

      new Invocation(newInstance.then(node()), node(BuiltIn.contextReturn))
          .transform(node(Common.value), newInstance);
    }

    {
      final Node stream = node();
      eval.then(stream);
      new Invocation(stream, node(BuiltIn.method)).literal(node(Common.name), node("codePoints"))
          .transform(node(Common.object), node(Common.value));

      final Node iterator = node();
      stream.then(iterator);
      new Invocation(iterator, node(BuiltIn.method)).literal(node(Common.name), node("iterator"))
          .transform(node(Common.object), stream);
    }
  }
}
