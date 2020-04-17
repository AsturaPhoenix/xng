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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.MapMaker;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.CompletableSubject;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.RequiredArgsConstructor;
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

  private transient Executor threadPool;
  private transient Subject<String> rxOutput;

  public enum Common {
    // invocation
    literal, transform, exceptionHandler,

    // When a value is assigned to this key, it is also assigned to the parent
    // context keyed by the invocation node.
    returnValue,

    // stack frames
    caller, invocation, context,

    javaClass, object, name, exception, source, value,

    // Used as a value node to indicate the EAV node triggered when a property is
    // unset.
    eavUnset
  }

  public enum BuiltIn {
    /**
     * Activates the node at {@link Common#value} in the context given by
     * {@link Common#context}, or the calling context if absent. This activation
     * does not block.
     * 
     * As an alternative, consider chaining off of EAV nodes instead of activating
     * contextual nodes.
     */
    activate {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) throws Exception {
        Node activationContext = context.node.properties.get(kb.node(Common.context));
        if (activationContext == null)
          activationContext = propertyDescent(context.node, kb.node(Common.caller), kb.node(Common.context));

        context.require(kb.node(Common.value)).activate((Context) activationContext.value);
        return null;
      }
    },
    /**
     * Get the node representing an entity-attribute-value tuple. This node is
     * automatically activated when a matching property is queried or written.
     * 
     * If {@link Common#value} is omitted, the wildcard EAV node is returned. To
     * retrieve the EAV node for the clearing of a property, pass
     * {@link Common#eavUnset}.
     */
    eavTuple {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) throws Exception {
        final Node object = context.node.properties.get(kb.node(Common.object));
        final Node property = context.require(kb.node(Common.name));
        final Node value = context.node.properties.get(kb.node(Common.value));

        return value == null ? kb.eavNode(object, property)
            : kb.eavNode(object, property, value.value == Common.eavUnset ? null : value);
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
    /**
     * Creates a new anonymous or invocation node. Invocation nodes are created by
     * specifying a {@link Common#value}, which serves as the entrypoint.
     */
    createNode {
      @Override
      public Node impl(final KnowledgeBase kb, Context context) {
        final Node invoke = context.node.properties.get(kb.node(Common.value));
        return invoke == null ? kb.node() : kb.node(kb.new Invocation(invoke));
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
    };

    public abstract Node impl(KnowledgeBase kb, Context context) throws Exception;
  }

  public KnowledgeBase() {
    preInit();
    postInit();
    bootstrap();
  }

  private void preInit() {
    preInitEav();

    rxOutput = PublishSubject.create();
    threadPool = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(), Integer.MAX_VALUE, 60,
        TimeUnit.SECONDS, new SynchronousQueue<>());
  }

  private void postInit() {
    postInitEav();

    for (final Node node : nodes) {
      initNode(node);
    }
  }

  /**
   * Create a context bound to this knowledge base, which registers its node
   * representation and uses a common thread pool.
   */
  public Context newContext() {
    return new Context(this::node, () -> threadPool);
  }

  private void initNode(final Node node) {
    // TODO: upgrade to java 14
    if (node.value instanceof BuiltIn) {
      node.setOnActivate(context -> Completable.fromAction(
          () -> setProperty(context, null, node(Common.returnValue), ((BuiltIn) node.value).impl(this, context))));
    } else if (node.value instanceof Invocation) {
      node.setOnActivate(context -> ((Invocation) node.value).onActivate(node, context));
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
    final Node parentNode = propertyDescent(context.node, node(Common.caller), node(Common.context));
    return parentNode == null ? context : (Context) parentNode.value;
  }

  /**
   * Invocation activates the specified node with a child context constructed from
   * {@link Common#literal} and {@link Common#transform}. Literal properties are
   * assigned directly to the new context, and transform properties are copied
   * from the parent context entry named by the value of the transform property.
   * Transform properties take precedence over literal properties if present,
   * allowing literals to act as defaults. The return value, if any, is assigned
   * to the context property named by the node with the invocation, whose
   * activation triggered the invocation.
   * 
   * Tail recursion can be achieved by setting the caller to the parent caller
   * (via {@code transform} or {@link Invocation#inherit(Node)}).
   */
  @RequiredArgsConstructor
  public class Invocation implements Serializable {
    private static final long serialVersionUID = -3534915519016230141L;

    private final Node invoke;

    @Override
    public String toString() {
      return "Invocation of " + invoke;
    }

    public Completable onActivate(final Node node, final Context parentContext) {
      final Context childContext = newContext();
      // Hold a ref while we're starting the invocation to avoid pulsing the EAV nodes
      // and making it look like we're transitioning to idle early.
      try (val ref = childContext.new Ref()) {
        final Node caller = node();
        setProperty(childContext, caller, node(Common.invocation), node);
        setProperty(childContext, caller, node(Common.context), parentContext.node);

        setProperty(childContext, null, node(Common.caller), caller);
        // Tie the parent activity to the child activity.
        childContext.rxActive().subscribe(new Observer<Boolean>() {
          Context.Ref ref;

          @Override
          public void onNext(Boolean active) {
            if (active) {
              assert ref == null;
              ref = parentContext.new Ref();
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
            e.printStackTrace();
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
              final Node value = parentContext.node.properties.get(mapping.getValue());
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
      }

      val completable = CompletableSubject.create();
      childContext.continuation().thenRun(completable::onComplete).exceptionally(t -> {
        completable.onError(t);
        return null;
      });
      return completable;
    }
  }

  public class InvocationBuilder {
    public final Node node;

    public InvocationBuilder(final Node invoke) {
      node = node(new Invocation(invoke));
    }

    public InvocationBuilder literal(final Node key, final Node value) {
      node.properties.computeIfAbsent(node(Common.literal), k -> node()).properties.put(key, value);
      return this;
    }

    public InvocationBuilder transform(final Node key, final Node value) {
      node.properties.computeIfAbsent(node(Common.transform), k -> node()).properties.put(key, value);
      return this;
    }

    public InvocationBuilder inherit(final Node key) {
      return transform(key, key);
    }

    public InvocationBuilder exceptionHandler(final Node exceptionHandler) {
      node.properties.put(node(Common.exceptionHandler), exceptionHandler);
      return this;
    }
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    preInit();
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
    postInit();
  }

  @Override
  public void close() {
    eavCleanupThread.interrupt();
    rxOutput.onComplete();
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

  private Map<EavTuple, Node> eavNodes = new ConcurrentHashMap<>();
  private transient ReferenceQueue<Node> eavCleanup;
  private transient Thread eavCleanupThread;

  private void preInitEav() {
    eavCleanup = new ReferenceQueue<>();
  }

  private void postInitEav() {
    eavCleanupThread = new Thread(() -> {
      try {
        while (!Thread.interrupted()) {
          ((EavTuple.CanonicalWeakReference) eavCleanup.remove()).destroyTuple();
        }
      } catch (InterruptedException e) {
      }
    });
    eavCleanupThread.start();
  }

  private Node eavNode(final Node... tuple) {
    return eavNodes.computeIfAbsent(new EavTuple(tuple), t -> {
      t.makeCanonical();
      return systemNode();
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

    eavNode(object, property, value).activate(context);
    eavNode(object, property).activate(context);
  }

  private final class EavTuple implements Serializable {
    private static final long serialVersionUID = 5006832330252764773L;

    class CanonicalWeakReference extends WeakReference<Node> {
      CanonicalWeakReference(final Node node) {
        super(node, eavCleanup);
      }

      void destroyTuple() {
        destroy();
      }
    }

    transient WeakReference<Node>[] tuple;
    transient int hashCode;

    EavTuple(final Node... tuple) {
      init(tuple);
    }

    @SuppressWarnings("unchecked")
    void init(final Node[] tuple) {
      this.tuple = new WeakReference[tuple.length];
      for (int i = 0; i < tuple.length; ++i) {
        this.tuple[i] = tuple[i] == null ? null : new WeakReference<>(tuple[i]);
      }
      hashCode = Arrays.asList(tuple).hashCode();
    }

    void makeCanonical() {
      for (int i = 0; i < tuple.length; ++i) {
        if (tuple[i] != null) {
          tuple[i] = new CanonicalWeakReference(tuple[i].get());
        }
      }
    }

    private void writeObject(final ObjectOutputStream stream) throws IOException {
      // Although all our fields look transient, we need to write KnowledgeBase.this.
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
        if (ref != null)
          ref.clear();
      }
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this)
        return true;
      if (!(obj instanceof EavTuple))
        return false;

      final EavTuple other = (EavTuple) obj;
      if (tuple.length != other.tuple.length)
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
        final Node invocation = propertyDescent(context.node, node(Common.caller), node(Common.invocation));
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

  // A type is marked immutable by its class node having a value property with a
  // canonicalizing map.
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
    return index.computeIfAbsent(resolvedValue, v -> createNodeWithHooks(v, true));
  }

  // All kb nodes must be created through a node(...) method to ensure the
  // proper callbacks are set.
  public Node node() {
    return createNodeWithHooks(null, true);
  }

  public Node systemNode() {
    return createNodeWithHooks(null, false);
  }

  private Node createNodeWithHooks(final Serializable value, final boolean hasSynapse) {
    final Node node = new Node(value, hasSynapse);
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
        final Node call = kb.new InvocationBuilder(kb.node(BuiltIn.method))
            .literal(kb.node(Common.name), kb.node("newInstance"))
            .transform(kb.node(Common.object), kb.node(Common.javaClass)).node;
        newInstance.then(call).then(kb.new InvocationBuilder(kb.node(BuiltIn.setProperty))
            .literal(kb.node(Common.name), kb.node(Common.returnValue)).transform(kb.node(Common.value), call).node);
      }
    },
    /**
     * <li>{@link Common#javaClass}: class to mark immutable
     */
    markImmutable {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node markImmutable) {
        final Node newMap = kb.new InvocationBuilder(kb.node(newInstance)).literal(kb.node(Common.javaClass),
            kb.node(ConcurrentHashMap.class)).node;

        final Node setValue = kb.new InvocationBuilder(kb.node(BuiltIn.setProperty))
            .literal(kb.node(Common.name), kb.node(Common.value))
            .transform(kb.node(Common.object), kb.node(Common.javaClass)).transform(kb.node(Common.value), newMap).node;

        markImmutable.then(newMap).then(setValue);

        // Mark some more common types immutable.
        for (final Class<?> type : new Class<?>[] { Boolean.class, Byte.class, Character.class, Integer.class,
            Long.class, Float.class, Double.class }) {
          kb.new InvocationBuilder(markImmutable).literal(kb.node(Common.javaClass), kb.node(type)).node
              .activate(kb.newContext());
        }
      }
    },
    /**
     * <li>{@link Common#value}: string to evaluate
     */
    eval {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node eval) {
        final Node stream = kb.new InvocationBuilder(kb.node(BuiltIn.method))
            .literal(kb.node(Common.name), kb.node("codePoints"))
            .transform(kb.node(Common.object), kb.node(Common.value)).node;
        eval.then(stream);

        final Node iterator = kb.new InvocationBuilder(kb.node(BuiltIn.method))
            .literal(kb.node(Common.name), kb.node("iterator")).transform(kb.node(Common.object), stream).node;
        stream.then(iterator);

        final Node hasNext = kb.new InvocationBuilder(kb.node(BuiltIn.method))
            .literal(kb.node(Common.name), kb.node("hasNext")).transform(kb.node(Common.object), iterator).node;
        iterator.then(hasNext);

        final Node next = kb.new InvocationBuilder(kb.node(BuiltIn.method))
            .literal(kb.node(Common.name), kb.node("next")).transform(kb.node(Common.object), iterator).node;
        kb.eavNode(null, hasNext, kb.node(true)).then(next);
      }
    };

    protected abstract void setUp(KnowledgeBase kb, Node node);
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
