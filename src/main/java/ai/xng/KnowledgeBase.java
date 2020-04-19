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
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Iterables;
import com.google.common.collect.MapMaker;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.CompletableSubject;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.RequiredArgsConstructor;
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
public class KnowledgeBase implements Serializable, AutoCloseable {
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

    // association
    prior, posterior, coefficient,

    /**
     * Used as a value node for the {@link BuiltIn#eavTuple} built-in to indicate
     * the EAV node triggered when a property is unset. This should not be used for
     * {@link KnowledgeBase#eavNode(Node...)}, where {@code null} should be used
     * directly instead.
     */
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
        return invoke == null ? new SynapticNode() : kb.new InvocationNode(invoke);
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

    protected abstract Node impl(KnowledgeBase kb, Context context) throws Exception;

    private Node node(final KnowledgeBase kb) {
      return new SynapticNode(this) {
        private static final long serialVersionUID = -307360749192088062L;

        @Override
        protected Completable onActivate(final Context context) {
          return Completable
              .fromAction(() -> kb.setProperty(context, null, kb.node(Common.returnValue), impl(kb, context)));
        }
      };
    }
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
  }

  /**
   * Create a context bound to this knowledge base, which registers its node
   * representation and uses a common thread pool.
   */
  public Context newContext() {
    return new Context(this::node, () -> threadPool);
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
   * Invocation nodes activate the specified entry point node with a child context
   * constructed from their {@link Common#literal} and {@link Common#transform}
   * properties. Literal properties are assigned directly to the new context, and
   * transform properties are copied from the parent context entry named by the
   * value of the transform property. Transform properties take precedence over
   * literal properties if present, allowing literals to act as defaults. The
   * return value, if any, is assigned to the context property named by the node
   * with the invocation, whose activation triggered the invocation.
   * 
   * Tail recursion can be achieved by setting the caller to the parent caller
   * (via {@code transform} or {@link #inherit(Node)}).
   */
  @RequiredArgsConstructor
  public class InvocationNode extends SynapticNode {
    private static final long serialVersionUID = 3233708822279852070L;

    private final Node invoke;

    @Override
    public String toString() {
      return "Invocation of " + invoke;
    }

    @Override
    protected Completable onActivate(final Context parentContext) {
      final Context childContext = newContext();
      // Hold a ref while we're starting the invocation to avoid pulsing the EAV nodes
      // and making it look like we're transitioning to idle early.
      try (val ref = childContext.new Ref()) {
        final Node caller = new SynapticNode();
        setProperty(childContext, caller, node(Common.invocation), this);
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

        final Node literal = properties.get(node(Common.literal));
        if (literal != null) {
          synchronized (literal.properties) {
            for (final Entry<Node, Node> mapping : literal.properties.entrySet()) {
              setProperty(childContext, null, mapping.getKey(), mapping.getValue());
            }
          }
        }

        final Node transform = properties.get(node(Common.transform));
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

        final Node exceptionHandler = properties.get(node(Common.exceptionHandler));
        if (exceptionHandler != null) {
          childContext.exceptionHandler = e -> {
            final Node exceptionNode = node(new ImmutableException(e));
            exceptionNode.properties.put(node(Common.source), this);
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

    public InvocationNode literal(final Node key, final Node value) {
      properties.computeIfAbsent(node(Common.literal), k -> new SynapticNode()).properties.put(key, value);
      return this;
    }

    public InvocationNode transform(final Node key, final Node value) {
      properties.computeIfAbsent(node(Common.transform), k -> new SynapticNode()).properties.put(key, value);
      return this;
    }

    public InvocationNode inherit(final Node key) {
      return transform(key, key);
    }

    public InvocationNode exceptionHandler(final Node exceptionHandler) {
      properties.put(node(Common.exceptionHandler), exceptionHandler);
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
  private static record Arg(int ordinal) implements Serializable {
    private static final long serialVersionUID = 1L;
  }

  /**
   * Immutable class labeling a positional parameter. This class is marked
   * immutable by the {@code KnowledgeBase}.
   */
  private static record Param(int ordinal) implements Serializable {
    private static final long serialVersionUID = 1L;
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
      return new Node();
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

      if (!(obj instanceof EavTuple other))
        return false;

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
    return index.computeIfAbsent(resolvedValue, v -> v instanceof BuiltIn b ? b.node(this) : new SynapticNode(v));
  }

  public enum Bootstrap {
    /**
     * <li>{@link Common#javaClass}: class for which to invoke default constructor.
     * <li>Returns the instance that was created.
     */
    newInstance {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node newInstance) {
        val call = kb.new InvocationNode(kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("newInstance"))
            .transform(kb.node(Common.object), kb.node(Common.javaClass));
        val setProperty = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .literal(kb.node(Common.name), kb.node(Common.returnValue)).transform(kb.node(Common.value), call);

        newInstance.then(call).then(setProperty);
      }
    },
    /**
     * <li>{@link Common#javaClass}: class to mark immutable
     */
    markImmutable {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node markImmutable) {
        val newMap = kb.new InvocationNode(kb.node(newInstance)).literal(kb.node(Common.javaClass),
            kb.node(ConcurrentHashMap.class));

        val setValue = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .literal(kb.node(Common.name), kb.node(Common.value))
            .transform(kb.node(Common.object), kb.node(Common.javaClass)).transform(kb.node(Common.value), newMap);

        markImmutable.then(newMap).then(setValue);

        // Mark some more common types immutable.
        for (final Class<?> type : new Class<?>[] { Boolean.class, Byte.class, Character.class, Integer.class,
            Long.class, Float.class, Double.class }) {
          kb.new InvocationNode(markImmutable).literal(kb.node(Common.javaClass), kb.node(type))
              .activate(kb.newContext());
        }
      }
    },
    /**
     * Resolves a string into a common/built-in/bootstrap node or the string node
     * itself.
     * <li>{@link Common#name}: node to resolve
     */
    resolve {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node resolve) {
        val index = new SynapticNode();
        for (val member : Iterables.concat(Arrays.asList(Common.values()), Arrays.asList(BuiltIn.values()),
            Arrays.asList(Bootstrap.values()))) {
          index.properties.put(kb.node(member.name()), kb.node(member));
        }
        val lookup = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).literal(kb.node(Common.object), index)
            .transform(kb.node(Common.name), kb.node(Common.name));
        resolve.then(lookup);

        val indexedResult = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .literal(kb.node(Common.name), kb.node(Common.returnValue)).transform(kb.node(Common.value), lookup);
        lookup.then(indexedResult);

        final Node absent = kb.eavNode(null, lookup, null);
        indexedResult.synapse.setCoefficient(absent, -1);

        val literalResult = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .literal(kb.node(Common.name), kb.node(Common.returnValue))
            .transform(kb.node(Common.value), kb.node(Common.name));
        absent.then(literalResult);
      }
    },
    /**
     * <li>{@link Common#value}: string to evaluate
     */
    eval {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node eval) {
        val stream = kb.new InvocationNode(kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("codePoints"))
            .transform(kb.node(Common.object), kb.node(Common.value));
        eval.then(stream);

        val iterator = kb.new InvocationNode(kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("iterator"))
            .transform(kb.node(Common.object), stream);
        stream.then(iterator);

        val hasNext = kb.new InvocationNode(kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("hasNext"))
            .transform(kb.node(Common.object), iterator);
        iterator.then(hasNext);

        val next = kb.new InvocationNode(kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("next"))
            .transform(kb.node(Common.object), iterator);
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
