package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.MapMaker;

import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.Value;
import lombok.val;

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

  /**
   * Map that keeps its nodes alive. Note that the {@code weakKeys} configuration
   * here is just to use identity keys with concurrency; since nodes have strong
   * references to their values, they will never actually be evicted.
   */
  private Map<Serializable, Node> strongIndex = new MapMaker().weakKeys().makeMap();
  /**
   * Map that does not keep its nodes alive. This is suitable only for values that
   * refer to their nodes, like {@link Context}s.
   */
  private Map<Serializable, Node> weakIndex = new MapMaker().weakKeys().weakValues().makeMap();
  private Collection<Node> nodes = Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

  private transient Subject<String> rxOutput;
  private transient Subject<Node> rxNodeAdded;

  public enum Common {
    // invocation
    invoke, literal, transform, exceptionHandler,

    // When a value is assigned to this key, it is also assigned to the parent
    // context keyed by the invocation node.
    returnValue,

    // stack frames
    invoker, invocation, context,

    // node tuple types
    contextPair, contextProperty, eavTriple, objectProperty,

    javaClass, object, name, exception, source, value, refractory, nodeCreated, nullNode
  }

  public enum BuiltIn {
    /**
     * Activates the node at {@link Common#value} in the context given by
     * {@link Common#context}, or the calling context if absent. This activation
     * does not block.
     */
    activate {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) throws Exception {
        Node activationContext = context.node.properties.get(kb.node(Common.context));
        if (activationContext == null)
          activationContext = propertyDescent(context.node, kb.node(Common.invoker), kb.node(Common.context));

        context.require(kb.node(Common.value)).activate((Context) activationContext.value);
        return null;
      }
    },
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
        final Node value = (object == null ? parent.node : object).properties.get(property);
        kb.activateEavNodes(context, object, property, value);
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
    preInitEav();
    init();
    bootstrap();
  }

  private void init() {
    postInitEav();

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

  private static Node propertyDescent(Node node, final Node... properties) {
    for (final Node property : properties) {
      if (node == null)
        return null;
      node = node.properties.get(property);
    }
    return node;
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
    final Node parentNode = propertyDescent(context.node, node(Common.invoker), node(Common.context));
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
   * Tail recursion can be achieved by setting the invoker to the parent invoker
   * (via {@code transform} or {@link Invocation#inherit(Node)}).
   * 
   * @param node
   * @param context
   */
  private void maybeInvoke(final Node node, final Context context) throws Exception {
    final Node invoke = node.properties.get(node(Common.invoke));
    if (invoke == null)
      return;

    final Context childContext = new Context(this::node);

    final Node invoker = node();
    setProperty(childContext, invoker, node(Common.invocation), node);
    setProperty(childContext, invoker, node(Common.context), context.node);

    setProperty(childContext, null, node(Common.invoker), invoker);
    // Tie the parent activity to the child activity.
    childContext.rxActive().subscribe(new Observer<Boolean>() {
      Context.Ref ref;

      @Override
      public void onNext(Boolean active) {
        if (active) {
          assert ref == null;
          ref = context.new Ref();
        } else if (!active && ref != null) {
          ref.close();
          ref = null;
        }
      }

      @Override
      public void onSubscribe(Disposable d) {
      }

      @Override
      public void onError(Throwable e) {
        if (ref != null)
          ref.close();
      }

      @Override
      public void onComplete() {
        if (ref != null)
          ref.close();
      }
    });

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
    // TODO(rosswang): This overrides maybeInvoke on the node. Is that alright?
    node.setOnActivate(context -> setProperty(context, null, node(Common.returnValue), builtIn.impl(this, context)));
    return node;
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    preInitEav();
    stream.defaultReadObject();
    val it = eavNodes.entrySet().iterator();
    while (it.hasNext()) {
      val entry = it.next();

      // Tuples that were serialized with an unreachable element are marked by a null
      // array.
      if (entry.getKey().tuple == null) {
        it.remove();
      } else {
        entry.getKey().makeCanonical();
      }
    }
    init();
  }

  @Override
  public void close() {
    eavCleanupThread.interrupt();
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

  private Map<NodeTuple, Node> eavNodes = new ConcurrentHashMap<>();
  private transient ReferenceQueue<Node> eavCleanup;
  private transient Thread eavCleanupThread;

  private void preInitEav() {
    eavCleanup = new ReferenceQueue<>();
  }

  private void postInitEav() {
    eavCleanupThread = new Thread(() -> {
      try {
        while (!Thread.interrupted()) {
          ((NodeTuple.CanonicalWeakReference) eavCleanup.remove()).destroyTuple();
        }
      } catch (InterruptedException e) {
      }
    });
    eavCleanupThread.start();
  }

  private Node eavNode(final Common type, final Node... tuple) {
    return eavNodes.computeIfAbsent(new NodeTuple(type, tuple), t -> {
      t.makeCanonical();
      return node();
    });
  }

  private void activateEavNodes(final Context context, Node object, final Node property, final Node value) {
    // EAV nodes are meant to capture specializations and generalizations, so EAV
    // nodes for ephemeral context values defeat the purpose and accumulate garbage
    // that would require nontrivial analysis to clean up (since they could
    // potentialy affect other state once they are connected through Hebbian
    // learning, it is not obvious in what cases it would be safe to cull them).
    // Thus, go ahead and omit those EAV nodes.
    if (object != null && object.value instanceof Context || value != null && value.value instanceof Context)
      return;
    // (The case of property.value instanceof context is not covered because what
    // does that even mean, and there would probably be an interesting reason for
    // doing something like that.)

    if (object == null) {
      eavNode(Common.contextPair, property, value).activate(context);
      eavNode(Common.contextProperty, property).activate(context);
    } else {
      eavNode(Common.eavTriple, object, property, value).activate(context);
      eavNode(Common.objectProperty, object, property).activate(context);
    }
  }

  private final class NodeTuple implements Serializable {
    private static final long serialVersionUID = 5070278620146038496L;

    class CanonicalWeakReference extends WeakReference<Node> {
      CanonicalWeakReference(final Node node) {
        super(node, eavCleanup);
      }

      void destroyTuple() {
        destroy();
      }
    }

    final Common type;
    transient WeakReference<Node>[] tuple;
    transient int hashCode;

    NodeTuple(final Common type, final Node[] tuple) {
      this.type = type;
      init(tuple);
    }

    @SuppressWarnings("unchecked")
    void init(final Node[] tuple) {
      this.tuple = new WeakReference[tuple.length];
      for (int i = 0; i < tuple.length; ++i) {
        this.tuple[i] = tuple[i] == null ? null : new WeakReference<>(tuple[i]);
      }
      hashCode = type.hashCode() ^ Arrays.asList(tuple).hashCode();
    }

    void makeCanonical() {
      for (int i = 0; i < tuple.length; ++i) {
        if (tuple[i] != null) {
          tuple[i] = new CanonicalWeakReference(tuple[i].get());
        }
      }
    }

    private void writeObject(final ObjectOutputStream stream) throws IOException {
      stream.defaultWriteObject();
      final Node[] tuple = new Node[this.tuple.length];
      for (int i = 0; i < tuple.length; ++i) {
        val ref = this.tuple[i];
        if (ref == null) {
          tuple[i] = null;
        } else {
          val node = ref.get();
          if (node == null) {
            // This will be evicted after deserialization.
            stream.writeObject(null);
            return;
          } else {
            tuple[i] = node;
          }
        }
      }
      stream.writeObject(tuple);
    }

    private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
      stream.defaultReadObject();
      val tuple = (Node[]) stream.readObject();
      if (tuple != null)
        init(tuple);
    }

    void destroy() {
      // Note that since this happens in response to a tuple element having become
      // unreachable, we don't really have to worry much about race conditions since
      // no write conflicts can arise at this point.
      eavNodes.remove(this);
      for (val ref : tuple) {
        ref.clear();
      }
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this)
        return true;
      if (!(obj instanceof NodeTuple))
        return false;

      final NodeTuple other = (NodeTuple) obj;
      if (type != other.type || tuple.length != other.tuple.length)
        return false;

      for (int i = 0; i < tuple.length; ++i) {
        if (tuple[i] == other.tuple[i])
          continue;
        if (tuple[i] == null || other.tuple[i] == null || tuple[i].get() != other.tuple[i].get())
          return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      return hashCode;
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

      if (property.value == Common.returnValue) {
        final Node invocation = propertyDescent(context.node, node(Common.invoker), node(Common.invocation));
        if (invocation != null) {
          setProperty(getParent(context), null, invocation, value);
        }
        context.continuation().complete(null);
      }
    } else {
      if (value == null) {
        old = object.properties.remove(property);
      } else {
        old = object.properties.put(property, value);
      }
    }

    activateEavNodes(context, object, property, value);

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

    final Map<Serializable, Node> index = resolvedValue instanceof Context ? weakIndex : strongIndex;
    return index.computeIfAbsent(resolvedValue, this::createNodeWithHooks);
  }

  // All kb nodes must be created through a node(...) method to ensure the
  // proper callbacks are set.
  public Node node() {
    return createNodeWithHooks(null);
  }

  private Node createNodeWithHooks(final Serializable value) {
    final Node node = new Node(value);
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
     * <li>{@link Common#javaClass}: class for which to invoke default constructor.
     * <li>Returns the instance that was created.
     */
    newInstance {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node newInstance) {
        kb.new Invocation(newInstance, kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("newInstance"))
            .transform(kb.node(Common.object), kb.node(Common.javaClass));

        kb.new Invocation(newInstance.then(kb.node()), kb.node(BuiltIn.setProperty))
            .literal(kb.node(Common.name), kb.node(Common.returnValue)).transform(kb.node(Common.value), newInstance);
      }
    },
    /**
     * <li>{@link Common#javaClass}: class to mark immutable
     */
    markImmutable {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node markImmutable) {
        kb.new Invocation(markImmutable, kb.node(newInstance)).literal(kb.node(Common.javaClass),
            kb.node(ConcurrentHashMap.class));

        kb.new Invocation(markImmutable.then(kb.node()), kb.node(BuiltIn.setProperty))
            .literal(kb.node(Common.name), kb.node(Common.value))
            .transform(kb.node(Common.object), kb.node(Common.javaClass))
            .transform(kb.node(Common.value), markImmutable);

        // Mark some more common types immutable.
        for (final Class<?> type : new Class<?>[] { Boolean.class, Byte.class, Character.class, Integer.class,
            Long.class, Float.class, Double.class }) {
          kb.new Invocation(kb.node(), markImmutable).literal(kb.node(Common.javaClass), kb.node(type)).node
              .activate(new Context(kb::node));
        }
      }
    },
    /**
     * Activates the property on {@link Common#object} named by {@link Common#name}
     * in the calling context. This can be used to implement conditional and switch
     * logic.
     */
    select {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node select) {
        final Node selection = select.then(kb.node());
        kb.new Invocation(selection, kb.node(BuiltIn.getProperty)).inherit(kb.node(Common.object))
            .inherit(kb.node(Common.name));
        // Activate in the calling context via tail recursion.
        kb.new Invocation(selection.then(kb.node()), kb.node(BuiltIn.activate))
            .transform(kb.node(Common.value), selection).inherit(kb.node(Common.invoker));
      }
    },
    /**
     * <li>{@link Common#value}: string to evaluate
     */
    eval {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node eval) {
        final Node stream = kb.node();
        eval.then(stream);
        kb.new Invocation(stream, kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("codePoints"))
            .transform(kb.node(Common.object), kb.node(Common.value));

        final Node iterator = kb.node();
        stream.then(iterator);
        kb.new Invocation(iterator, kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("iterator"))
            .transform(kb.node(Common.object), stream);

        final Node hasNext = kb.node();
        iterator.then(hasNext);
        kb.new Invocation(hasNext, kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("hasNext"))
            .transform(kb.node(Common.object), iterator);

        final Node ifHasNext = kb.node();
        hasNext.then(ifHasNext);
        final Node next = kb.node();
        {
          final Node ifHasNextDispatch = kb.node();
          ifHasNextDispatch.properties.put(kb.node(true), next);
          kb.new Invocation(ifHasNext, kb.node(select)).literal(kb.node(Common.object), ifHasNextDispatch)
              .transform(kb.node(Common.name), hasNext);
        }

        kb.new Invocation(next, kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("next"))
            .transform(kb.node(Common.object), iterator);

        final Node activateChar = kb.node();
        next.then(activateChar);
        kb.new Invocation(activateChar, kb.node(BuiltIn.activate)).transform(kb.node(Common.value), next)
            .inherit(kb.node(Common.invoker));
      }
    };

    protected abstract void setUp(KnowledgeBase kb, Node node);
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

    public Invocation inherit(final Node key) {
      return transform(key, key);
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
    // We also need to mark args and params as immutable as they're potentially
    // touched in the markImmutable invocation.
    markImmutable(Arg.class);
    markImmutable(Param.class);

    for (val bootstrap : Bootstrap.values()) {
      bootstrap.setUp(this, node(bootstrap));
    }
  }
}
