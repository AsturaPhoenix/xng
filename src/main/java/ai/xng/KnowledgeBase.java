package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.RequiredArgsConstructor;
import lombok.Value;

/**
 * Since this is meant to be a proof of concept, encapsulation is not perfect.
 * Particularly, the following external operations should be avoided:
 * 
 * <ul>
 * <li>{@link Node#setOnActivate(java.util.function.Consumer)}
 * <li>mutators on {@link Context#index}
 * </ul>
 */
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

  private transient Collection<Node> nodes = new ArrayList<>();

  private transient Subject<String> rxOutput;
  private transient Subject<Node> rxNodeAdded;

  public enum Common {
    // invocation
    invoke, literal, transform, exceptionHandler,

    // When a value is assigned to this key, it is also assigned to the parent
    // context keyed by the invocation node.
    returnValue,

    parentContext,

    // node tuple types
    contextPair, contextProperty, eavTriple, objectProperty,

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
        final Node classNode = context.node.properties.get(kb.node(Common.javaClass)),
            objectNode = context.node.properties.get(kb.node(Common.object)),
            fieldNode = context.require(kb.node(Common.name));
        final Object object;
        final Class<?> clazz;

        if (objectNode != null) {
          object = objectNode.value;

          if (classNode != null) {
            clazz = (Class<?>) classNode.value;
            if (!clazz.isAssignableFrom(object.getClass())) {
              throw new IllegalArgumentException("Provided class does not match object class");
            }
          } else {
            clazz = object.getClass();
          }
        } else {
          object = null;
          clazz = (Class<?>) classNode.value;
        }

        Field field = clazz.getField((String) fieldNode.value);

        Object ret = field.get(object);
        return kb.node((Serializable) ret);
      }
    },
    /**
     * Takes two args: {@link Common#object} and {@link Common#name}. If
     * {@code object} is omitted, gets the property from the parent context.
     * 
     * Additionally, invokes AV/EAV nodes for the retrieved property in the parent
     * context.
     */
    getProperty {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) {
        final Node object = context.node.properties.get(kb.node(Common.object)),
            property = context.require(kb.node(Common.name));
        final Context parent = kb.getParent(context);
        final Node value;

        if (object == null) {
          value = parent.node.properties.get(property);
          kb.node(kb.new NodeTuple(Common.contextPair, property, value)).activate(parent);
          kb.node(kb.new NodeTuple(Common.contextProperty, property)).activate(parent);
        } else {
          value = object.properties.get(property);
          kb.node(kb.new NodeTuple(Common.eavTriple, object, property, value)).activate(parent);
          kb.node(kb.new NodeTuple(Common.objectProperty, object, property)).activate(parent);
        }

        return value;
      }
    },
    /**
     * A limited Java interop bridge. Method parameter types must be fully and
     * exactly specified via "paramN" index entries, null-terminated, having values
     * of Java classes. Arguments are passed as "argN" index entries, and must be
     * serializable. Missing arguments are passed null.
     * 
     * {@link Common#javaClass}: optional class on which to invoke method. If
     * {@link Common#object} is also provided, this must be a supertype.
     * {@link Common#object}: optional object on which to invoke method.
     * {@link Common#name}: required name of the method to invoke.
     */
    method {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) throws Exception {
        final Node classNode = context.node.properties.get(kb.node(Common.javaClass)),
            objectNode = context.node.properties.get(kb.node(Common.object)),
            methodNode = context.require(kb.node(Common.name));
        final Object object;
        final Class<?> clazz;

        if (objectNode != null) {
          object = objectNode.value;

          if (classNode != null) {
            clazz = (Class<?>) classNode.value;
            if (!clazz.isAssignableFrom(object.getClass())) {
              throw new IllegalArgumentException("Provided class does not match object class");
            }
          } else {
            clazz = object.getClass();
          }
        } else {
          object = null;
          clazz = (Class<?>) classNode.value;
        }

        ArrayList<Class<?>> params = new ArrayList<>();
        Node param;
        for (int i = 1; (param = context.node.properties.get(kb.param(i))) != null; i++) {
          params.add((Class<?>) param.value);
        }

        Method method = clazz.getMethod((String) methodNode.value, params.toArray(new Class<?>[0]));
        ArrayList<Object> args = new ArrayList<>();
        for (int i = 1; i <= params.size(); i++) {
          Node arg = context.node.properties.get(kb.arg(i));
          args.add(arg == null ? null : arg.value);
        }

        Object ret = method.invoke(object, args.toArray());
        return method.getReturnType() == null ? null : kb.node((Serializable) ret);
      }
    },
    findClass {
      @Override
      public Node impl(final KnowledgeBase kb, Context context) throws ClassNotFoundException {
        return kb.node(Class.forName((String) context.require(kb.node(Common.name)).value));
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
        kb.rxOutput.onNext(Objects.toString(context.require(kb.node(Common.value)).value));
        return null;
      }
    },
    /**
     * <li>{@link Common#object}: optional node on which to set a property. If
     * omitted, sets the property on the parent context.
     * <li>{@link Common#name}: required property name.
     * <li>{@link Common#value}: optional value to set. If null, the property is
     * cleared.
     * <li>Returns the value previously at this property.
     * 
     * Additionally, invokes AV/EAV nodes for the new property in the parent
     * context.
     * 
     * The special context key {@link Common#returnValue} is mirrored to the parent
     * context keyed by the invocation node, and may trigger the continuation of the
     * invocation activation chain.
     */
    setProperty {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) {
        return kb.setProperty(kb.getParent(context), context.node.properties.get(kb.node(Common.object)),
            context.require(kb.node(Common.name)), context.node.properties.get(kb.node(Common.value)));
      }
    },
    setRefractory {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) {
        context.require(kb.node(Common.value))
            .setRefractory(((Number) context.require(kb.node(Common.refractory)).value).longValue());
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
   * Attempts to get the parent context of a given context. If the context does
   * not have a parent, the original context is used instead. If the parent node
   * is not a context, {@link ClassCastException} is thrown.
   * 
   * There is also an edge case where the parent node has a null value, in which
   * case null is returned.
   */
  private Context getParent(final Context context) {
    final Node parentNode = context.node.properties.get(node(Common.parentContext));
    return parentNode == null ? context : (Context) parentNode.value;
  }

  /**
   * Invocation activates the node specified by {@link Common#invoke} with a child
   * context constructed from {@link Common#literal} and {@link Common#transform}.
   * Literal properties are assigned directly to the new context, and transform
   * properties are copied from the parent context entry named by the value of the
   * transform property. Transform properties take precedence over literal
   * properties if present, allowing literals to act as defaults. The return
   * value, if any, is assigned to the context property named by the node with the
   * invocation, whose activation triggered the invocation.
   * 
   * @param node
   * @param context
   */
  private void maybeInvoke(final Node node, final Context context) throws Exception {
    final Node invoke = node.properties.get(node(Common.invoke));
    if (invoke == null)
      return;

    final Context childContext = new Context(this::node, node);
    setProperty(childContext, null, node(Common.parentContext), context.node);

    final Node literal = node.properties.get(node(Common.literal));
    if (literal != null) {
      synchronized (literal.properties) {
        for (final Entry<Node, Node> mapping : literal.properties.entrySet()) {
          setProperty(childContext, null, mapping.getKey(), mapping.getValue());
        }
      }
    }

    final Node transform = node.properties.get(node(Common.transform));
    if (transform != null) {
      synchronized (transform.properties) {
        for (final Entry<Node, Node> mapping : transform.properties.entrySet()) {
          final Node value = context.node.properties.get(mapping.getValue());
          if (value != null) {
            setProperty(childContext, null, mapping.getKey(), value);
          }
        }
      }
    }

    final Node exceptionHandler = node.properties.get(node(Common.exceptionHandler));
    if (exceptionHandler != null) {
      childContext.exceptionHandler = e -> {
        final Node exceptionNode = node(new ImmutableException(e));
        exceptionNode.properties.put(node(Common.source), node);
        // Right now we use the same context for the exception handler, so if another
        // exception is thrown the exception handler is invoked again (and is likely to
        // hit a refractory dedup). We should consider changing this once contexts can
        // inherit activations.
        setProperty(childContext, null, node(Common.exception), exceptionNode);
        exceptionHandler.activate(childContext);
      };
    }

    invoke.activate(childContext);

    try {
      childContext.continuation().join();
    } catch (final CompletionException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      } else {
        throw e;
      }
    }
  }

  private Node registerBuiltIn(final BuiltIn builtIn) {
    // TODO(rosswang): Keep the original built-in impl node indexed separate
    // from the main index so that we can rebind the impl to the correct
    // node after deserialization.
    final Node node = node(builtIn);
    node.setOnActivate(context -> setProperty(context, null, node(Common.returnValue), builtIn.impl(this, context)));
    return node;
  }

  private void writeObject(final ObjectOutputStream o) throws IOException {
    o.defaultWriteObject();
    synchronized (nodes) {
      o.writeObject(nodes);
    }
  }

  @SuppressWarnings("unchecked")
  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    stream.defaultReadObject();
    nodes = (Collection<Node>) stream.readObject();

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

  /**
   * Immutable class labeling a positional argument. This class is marked
   * immutable by the {@code KnowledgeBase}.
   */
  @Value
  private class Arg implements Serializable {
    private static final long serialVersionUID = 1L;
    int ordinal;
  }

  /**
   * Immutable class labeling a positional parameter. This class is marked
   * immutable by the {@code KnowledgeBase}.
   */
  @Value
  private class Param implements Serializable {
    private static final long serialVersionUID = 1L;
    int ordinal;
  }

  /**
   * Gets or creates a node representing a positional argument. These are
   * typically property names under {@code ARGUMENT} nodes.
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

  /**
   * Class representing a variant of node tuple. Although not technically
   * immutable, since the node semantics are by identity, this class is marked
   * immutable by the {@code KnowledgeBase}.
   */
  @Value
  private class NodeTuple implements Serializable {
    private static final long serialVersionUID = 5070278620146038496L;
    Node type;
    List<Node> tuple;

    public NodeTuple(final Common type, final Node... tuple) {
      this.type = node(type);
      this.tuple = Collections.unmodifiableList(Arrays.asList(tuple));
    }
  }

  /**
   * @param context the context to be used if {@code object} is null. This is also
   *                the context in which EAV nodes are activated.
   * @return the previous value of the property, if any.
   */
  private Node setProperty(final Context context, final Node object, final Node property, final Node value) {
    final Node old;

    if (object == null) {
      if (value == null) {
        old = context.node.properties.remove(property);
      } else {
        old = context.node.properties.put(property, value);
      }
      node(new NodeTuple(Common.contextPair, property, value)).activate(context);
      node(new NodeTuple(Common.contextProperty, property)).activate(context);

      if (property.value == Common.returnValue) {
        if (context.invocation != null) {
          setProperty(getParent(context), null, context.invocation, value);
        }
        context.continuation().complete(null);
      }
    } else {
      if (value == null) {
        old = object.properties.remove(property);
      } else {
        old = object.properties.put(property, value);
      }
      node(new NodeTuple(Common.eavTriple, object, property, value)).activate(context);
      node(new NodeTuple(Common.objectProperty, object, property)).activate(context);
    }

    return old;
  }

  private void markImmutable(Class<?> type) {
    node(type).properties.put(node(Common.value), node(new ConcurrentHashMap<Serializable, Serializable>()));
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
      if (values != null && values.value instanceof ConcurrentHashMap) {
        resolvingValue = ((ConcurrentHashMap<Serializable, Serializable>) values.value).computeIfAbsent(value,
            x -> value);
      }
    }

    final Serializable resolvedValue = resolvingValue;

    return valueIndex.computeIfAbsent(new IdentityKey(resolvedValue), x -> {
      final Node node = new Node(resolvedValue);
      initNode(node);
      synchronized (nodes) {
        nodes.add(node);
      }
      return node;
    });
  }

  // All kb nodes must be created through a node(...) method to ensure the
  // proper callbacks are set.
  public Node node() {
    final Node node = new Node();
    initNode(node);
    synchronized (nodes) {
      nodes.add(node);
    }
    return node;
  }

  @Override
  public Iterator<Node> iterator() {
    return nodes.iterator();
  }

  public Object iteratorMutex() {
    return nodes;
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
      node.properties.computeIfAbsent(node(Common.transform), k -> node()).properties.put(key, value);
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
    markImmutable(String.class);
    // We also need to mark args, params, and node tuples as immutable as they're
    // all potentially touched in the markImmutable invocation.
    markImmutable(Arg.class);
    markImmutable(Param.class);
    markImmutable(NodeTuple.class);

    final Node markImmutable = node(Bootstrap.markImmutable), newInstance = node(Bootstrap.newInstance),
        eval = node(Bootstrap.eval);

    {
      new Invocation(markImmutable, newInstance).literal(node(Common.javaClass), node(ConcurrentHashMap.class));

      new Invocation(markImmutable.then(node()), node(BuiltIn.setProperty))
          .literal(node(Common.name), node(Common.value)).transform(node(Common.object), node(Common.javaClass))
          .transform(node(Common.value), markImmutable);
    }

    {
      new Invocation(newInstance, node(BuiltIn.method)).literal(node(Common.name), node("newInstance"))
          .transform(node(Common.object), node(Common.javaClass));

      new Invocation(newInstance.then(node()), node(BuiltIn.setProperty))
          .literal(node(Common.name), node(Common.returnValue)).transform(node(Common.value), newInstance);
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
