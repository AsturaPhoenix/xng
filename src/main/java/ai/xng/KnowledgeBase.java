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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapMaker;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.CompletableSubject;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
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
  private transient Map<Object, Node> strongIndex;
  /**
   * Map that does not keep its nodes alive. This is suitable only for values that
   * refer to their nodes, like {@link Context}s.
   */
  private transient Map<Object, Node> weakIndex;

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

    javaClass, object, name, exception, source, value, entrypoint, relative,

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
      public Node impl(final KnowledgeBase kb, final Context context) {
        Node activationContext = context.node.properties.get(kb.node(Common.context));
        if (activationContext == null)
          activationContext = propertyDescent(context.node, kb.node(Common.caller), kb.node(Common.context));

        context.require(kb.node(Common.value)).activate((Context) activationContext.getValue());
        return null;
      }
    },
    associate {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) {
        final Node prior = context.require(kb.node(Common.prior));
        final SynapticNode posterior = (SynapticNode) context.require(kb.node(Common.posterior));
        final Node coefficient = context.node.properties.get(kb.node(Common.coefficient));

        posterior.synapse.setCoefficient(prior, coefficient == null ? 1 : (float) coefficient.getValue());
        return null;
      }
    },
    /**
     * Get the node representing an entity-attribute-value tuple. This node is
     * automatically activated when a matching property is queried or written.
     * 
     * If only one of {@link Common#object} or {@link Common#name} is specified, a
     * relative EAV node to the contextual EAV node for the specified node is
     * returned. Alternatively, {@link Common#relative} is a boolean to indicate a
     * fully specified relative EAV node.
     * 
     * If {@link Common#value} is omitted, the wildcard EAV node is returned. To
     * retrieve the EAV node for the clearing of a property, pass
     * {@link Common#eavUnset}.
     */
    eavTuple {
      @Override
      public Node impl(final KnowledgeBase kb, final Context context) {
        final Node object = context.node.properties.get(kb.node(Common.object));
        final Node name = context.node.properties.get(kb.node(Common.name));
        final Node value = context.node.properties.get(kb.node(Common.value));
        final Node relative = context.node.properties.get(kb.node(Common.relative));

        val path = new ArrayList<Node>(3);
        if (object != null)
          path.add(object);
        if (name != null)
          path.add(name);
        final boolean srslyRelative = path.size() == 1 || (boolean) relative.getValue();

        if (value != null)
          path.add(value.getValue() == Common.eavUnset ? null : value);

        return kb.eavNode(srslyRelative, value == null, path.toArray(new Node[] {}));
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
        return kb.node(field.get(object));
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
     * of Java classes. Arguments are passed as "argN" index entries. Missing
     * arguments are passed null.
     * <p>
     * If the class is not explicitly specified, this bridge may attempt to use the
     * inaccessible concrete class of the object. More mature knowledge bases should
     * implement a more intelligent interop mechanism, which may include better
     * resolution, caching, and/or lambda metafactory.
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
        for (int i = 1; (param = context.node.properties.get(kb.param(i))) != null; i++) {
          params.add((Class<?>) param.getValue());
        }

        Method method = clazz.getMethod((String) methodNode.getValue(), params.toArray(new Class<?>[0]));
        ArrayList<Object> args = new ArrayList<>();
        for (int i = 1; i <= params.size(); i++) {
          Node arg = context.node.properties.get(kb.arg(i));
          args.add(arg == null ? null : arg.getValue());
        }

        final Object ret = method.invoke(object, args.toArray());
        return method.getReturnType() == null ? null : kb.node(ret);
      }
    },
    findClass {
      @Override
      public Node impl(final KnowledgeBase kb, Context context) throws ClassNotFoundException {
        return kb.node(Class.forName((String) context.require(kb.node(Common.name)).getValue()));
      }
    },
    /**
     * Creates a new anonymous node. Invocation nodes are created by specifying a
     * {@link Common#entrypoint}, which serves as the entrypoint.
     */
    node {
      @Override
      public Node impl(final KnowledgeBase kb, Context context) {
        final Node entrypoint = context.node.properties.get(kb.node(Common.entrypoint));
        return entrypoint == null ? new SynapticNode() : kb.new InvocationNode(entrypoint);
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
    strongIndex = new MapMaker().weakKeys().makeMap();
    weakIndex = new MapMaker().weakKeys().weakValues().makeMap();

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
    return parentNode == null ? context : (Context) parentNode.getValue();
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
  public class InvocationNode extends SynapticNode {
    private static final long serialVersionUID = 3233708822279852070L;

    // This is only for debug and will get less useful as the knowledge base
    // evolves.
    private final List<StackTraceElement> trace;

    public InvocationNode(final Node invoke) {
      super(invoke);
      val fullTrace = Arrays.asList(Thread.currentThread().getStackTrace());
      trace = ImmutableList.copyOf(fullTrace.subList(2, fullTrace.size()));
    }

    @Override
    public String toString() {
      val sb = new StringBuilder();
      if (comment != null) {
        sb.append(comment).append(": ");
      }
      sb.append("Invocation of ").append(getValue());
      return sb.toString();
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

              // Note that this implies that missing transform values will not have their
              // null-value EAV nodes pulsed, but nor will they have their EAV set nodes
              // pulsed. While different from the behavior for literals, this is akin to the
              // argument not being passed in, and is more consistent for tail recursion.
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

        ((Node) getValue()).activate(childContext);
      }

      val completable = CompletableSubject.create();
      childContext.continuation().thenRun(completable::onComplete).exceptionally(t -> {
        System.err.print("from ");
        System.err.println(this);
        for (val traceElement : trace) {
          System.err.print("        at ");
          System.err.println(traceElement);
        }
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

  private static void writeIndex(final Map<Object, Node> index, final ObjectOutputStream stream) throws IOException {
    // Discard keys that aren't serializable.
    for (val entry : index.entrySet()) {
      if (entry.getKey() instanceof Serializable) {
        stream.writeObject(entry.getKey());
        stream.writeObject(entry.getValue());
      }
    }
    stream.writeObject(null);
  }

  private void writeObject(final ObjectOutputStream stream) throws IOException {
    stream.defaultWriteObject();
    writeIndex(strongIndex, stream);
    writeIndex(weakIndex, stream);
  }

  private static void readIndex(final Map<Object, Node> index, final ObjectInputStream stream)
      throws IOException, ClassNotFoundException {
    Object key;
    while ((key = stream.readObject()) != null) {
      index.put(key, (Node) stream.readObject());
    }
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    preInit();
    stream.defaultReadObject();

    readIndex(strongIndex, stream);
    readIndex(weakIndex, stream);

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

  private Node eavNode(final boolean relative, final boolean wildcard, final Node... tuple) {
    return eavNodes.computeIfAbsent(new EavTuple(relative, wildcard, tuple), t -> {
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
    if (object != null && object.getValue() instanceof Context || value != null && value.getValue() instanceof Context)
      return;
    // (The case of property.value instanceof context is not covered because what
    // does that even mean, and there would probably be an interesting reason for
    // doing something like that.)

    // In addition to top-level contextual nodes, we also activate EAV nodes for the
    // first layer of properties of a contextual node. This is because there's no
    // other way to watch "references" to nodes.

    // For now, we don't attempt to activate EAV nodes across contexts, even if
    // we're setting properties on a context node. This may change in the future.
    // For now it can be seen kind of like synchronization in Java.

    if (object == null) {
      // It's important to activate specific nodes first and then wildcard nodes so
      // that we can inhibit on particular values. By the same logic, activate
      // properties before main nodes.
      if (value != null) {
        // We need to collect prior to activating since we're iterating over properties
        // and activation could alter the context.
        val propEavs = new ArrayList<Node>(2 * value.properties.size());
        synchronized (value.properties) {
          for (val entry : value.properties.entrySet()) {
            propEavs.add(eavNode(true, false, property, entry.getKey(), entry.getValue()));
            propEavs.add(eavNode(true, true, property, entry.getKey()));
          }
        }
        for (val node : propEavs) {
          node.activate(context);
        }
      }

      eavNode(true, false, property, value).activate(context);
      eavNode(true, true, property).activate(context);

    } else {
      eavNode(false, false, object, property, value).activate(context);
      eavNode(false, true, object, property).activate(context);

      // Also find contextual references.
      // We need to collect prior to activating since we're iterating over properties
      // and activation could alter the context.
      val refEavs = new ArrayList<Node>();
      synchronized (context.node.properties) {
        for (val entry : context.node.properties.entrySet()) {
          if (entry.getValue() == object) {
            refEavs.add(eavNode(true, false, entry.getKey(), property, value));
            refEavs.add(eavNode(true, true, entry.getKey(), property));
          }
        }
      }
      for (val node : refEavs) {
        node.activate(context);
      }
    }
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
    final boolean relative, wildcard;

    EavTuple(final boolean relative, final boolean wildcard, final Node... tuple) {
      this.relative = relative;
      this.wildcard = wildcard;
      init(tuple);
    }

    @SuppressWarnings("unchecked")
    void init(final Node[] tuple) {
      this.tuple = new WeakReference[tuple.length];
      for (int i = 0; i < tuple.length; ++i) {
        this.tuple[i] = tuple[i] == null ? null : new WeakReference<>(tuple[i]);
      }
      hashCode = Arrays.deepHashCode(new Object[] { relative, wildcard, tuple });
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

      if (relative != other.relative || wildcard != other.wildcard)
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

      if (property.getValue() == Common.returnValue) {
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

  // The index doesn't allow nulls and we can short-circuit most of our logic, so
  // special-case this here.
  private final Node nullNode = new SynapticNode();

  /**
   * Single-instance resolution for immutable types. Skip for/bootstrap with Class
   * and enum since they're always single-instance.
   * 
   * An immutable class is designated by having the node for the class instance
   * have a Common.value property that has a value of a ConcurrentHashMap, which
   * will be used to resolve values to canonical instances.
   */
  @SuppressWarnings("unchecked")
  private Serializable canonicalize(Serializable value) {
    if (value instanceof Class<?> || value instanceof Enum)
      return value;

    final Node values = node(value.getClass()).properties.get(node(Common.value));
    if (values != null && values.getValue() instanceof ConcurrentHashMap) {
      return ((ConcurrentHashMap<Serializable, Serializable>) values.getValue()).computeIfAbsent(value, x -> value);
    } else {
      return value;
    }
  }

  public Node node(Object value) {
    if (value == null)
      return nullNode;

    if (value instanceof Serializable s) {
      value = canonicalize(s);
    }

    final Map<Object, Node> index = value instanceof Context ? weakIndex : strongIndex;
    return index.computeIfAbsent(value, v -> v instanceof BuiltIn b ? b.node(this) : new SynapticNode(v));
  }

  /**
   * Adds a debug listener that dumps the context when a node is activated.
   */
  public static void debug(final Node node) {
    node.rxActivate()
        .subscribe(a -> System.err.println(a.context.node.debugDump(e -> e.getKey().getValue() != Common.caller)));
  }

  // The ordering of the members is significant; later setups can depend on
  // earlier ones.
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
          val context = kb.newContext();
          kb.new InvocationNode(markImmutable).literal(kb.node(Common.javaClass), kb.node(type)).activate(context);
          context.blockUntilIdle();
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

        final Node absent = kb.eavNode(true, false, lookup, null);
        indexedResult.synapse.setCoefficient(absent, -1);

        val literalResult = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .literal(kb.node(Common.name), kb.node(Common.returnValue))
            .transform(kb.node(Common.value), kb.node(Common.name));
        absent.then(literalResult);
      }
    },
    /**
     * <li>{@link Common#value}: string to parse
     */
    parse {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node parse) {
        val stream = kb.new InvocationNode(kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("codePoints"))
            .transform(kb.node(Common.object), kb.node(Common.value));
        parse.then(stream);

        val iterator = kb.new InvocationNode(kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("iterator"))
            .literal(kb.node(Common.javaClass), kb.node(IntStream.class)).transform(kb.node(Common.object), stream);
        stream.then(iterator);

        val hasNext = kb.new InvocationNode(kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("hasNext"))
            .literal(kb.node(Common.javaClass), kb.node(Iterator.class)).transform(kb.node(Common.object), iterator);
        iterator.then(hasNext);

        val next = kb.new InvocationNode(kb.node(BuiltIn.method)).literal(kb.node(Common.name), kb.node("next"))
            .literal(kb.node(Common.javaClass), kb.node(Iterator.class)).transform(kb.node(Common.object), iterator);
        kb.eavNode(true, false, hasNext, kb.node(true)).then(next);

        val onNext = new SynapticNode();
        indexNode(onNext, "onNext", parse, kb);

        val state = kb.new InvocationNode(kb.node(BuiltIn.node));
        indexNode(state, "state", parse, kb);
        parse.then(state);
        val stateReady = kb.eavNode(true, true, state);

        val onNextWithNext = kb.new InvocationNode(onNext).transform(kb.node(Common.value), next).inherit(state);
        onNextWithNext.conjunction(next, stateReady);

        val recurse = kb.new InvocationNode(hasNext).inherit(kb.node(Common.caller)).inherit(iterator).inherit(state);
        onNextWithNext.then(recurse);

        val eol = kb.eavNode(true, false, hasNext, kb.node(false));
        val finalOnNext = kb.new InvocationNode(onNext).inherit(state);
        finalOnNext.conjunction(eol, stateReady);

        // onNext: called for each character as Common.value, and at EOL without it.
        // state is inherited.
        {
          val hasValue = kb.eavNode(true, true, kb.node(Common.value));

          val identifier = new SynapticNode();
          indexNode(identifier, "identifier", parse, kb);
          {
            val buffer = kb.new InvocationNode(kb.node(Bootstrap.newInstance)).literal(kb.node(Common.javaClass),
                kb.node(StringBuilder.class));
            buffer.comment = "buffer";
            {
              val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
                  .literal(kb.node(Common.name), buffer).transform(kb.node(Common.value), buffer);
              buffer.then(publish);
            }

            val getBuffer = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
                .literal(kb.node(Common.name), buffer);
            val cacheBuffer = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).literal(kb.node(Common.name), buffer)
                .transform(kb.node(Common.value), getBuffer);
            getBuffer.then(cacheBuffer);

            val notStarted = new SynapticNode(), inProgress = new SynapticNode();
            indexNode(notStarted, "notStarted", identifier, kb);
            indexNode(inProgress, "inProgress", identifier, kb);

            val hasBuffer = kb.eavNode(true, true, state, buffer);

            notStarted.synapse.setCoefficient(onNext, 1);
            notStarted.synapse.setCoefficient(hasBuffer, -1);

            val hadBuffer = new SynapticNode();
            hadBuffer.synapse.setCoefficient(hasBuffer, 1);
            hadBuffer.synapse.setCoefficient(onNext, -1);
            getBuffer.conjunction(onNext, hadBuffer);
            cacheBuffer.then(inProgress);

            // Transcribe identifier character
            val ifIdentifierStart = kb.new InvocationNode(kb.node(BuiltIn.method))
                .literal(kb.node(Common.javaClass), kb.node(Character.class))
                .literal(kb.node(Common.name), kb.node("isJavaIdentifierStart"))
                .literal(kb.param(1), kb.node(int.class)).transform(kb.arg(1), kb.node(Common.value));
            ifIdentifierStart.conjunction(notStarted, hasValue);
            kb.eavNode(true, false, ifIdentifierStart, kb.node(true)).then(buffer);

            val append = kb.new InvocationNode(kb.node(BuiltIn.method)).transform(kb.node(Common.object), buffer)
                .literal(kb.node(Common.name), kb.node("appendCodePoint")).literal(kb.param(1), kb.node(int.class))
                .transform(kb.arg(1), kb.node(Common.value));
            buffer.then(append);

            val ifIdentifierPart = kb.new InvocationNode(kb.node(BuiltIn.method))
                .literal(kb.node(Common.javaClass), kb.node(Character.class))
                .literal(kb.node(Common.name), kb.node("isJavaIdentifierPart")).literal(kb.param(1), kb.node(int.class))
                .transform(kb.arg(1), kb.node(Common.value));
            ifIdentifierPart.conjunction(inProgress, hasValue);
            kb.eavNode(true, false, ifIdentifierPart, kb.node(true)).then(append);

            val collect = kb.new InvocationNode(kb.node(BuiltIn.method)).transform(kb.node(Common.object), buffer)
                .literal(kb.node(Common.name), kb.node("toString"));
            val resetBuffer = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
                .transform(kb.node(Common.object), state).literal(kb.node(Common.name), buffer);
            collect.then(resetBuffer);
            {
              val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
                  .literal(kb.node(Common.name), identifier).transform(kb.node(Common.value), collect);
              collect.then(publish);
            }

            kb.eavNode(true, false, ifIdentifierPart, kb.node(false)).then(collect);
            val noValue = new SynapticNode();
            noValue.synapse.setCoefficient(inProgress, 1);
            noValue.synapse.setCoefficient(hasValue, -1);
            noValue.then(collect);
          }

          val resolvedIdentifier = new SynapticNode();
          indexNode(resolvedIdentifier, "resolvedIdentifier", parse, kb);
          {
            val getIdentifier = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
                .transform(kb.node(Common.object), state).literal(kb.node(Common.name), identifier);
            val unset = kb.eavNode(true, false, state, identifier, null);
            val set = kb.eavNode(true, true, state, identifier);
            val notAlreadySet = new SynapticNode();
            notAlreadySet.synapse.setCoefficient(set, -1);
            notAlreadySet.synapse.setCoefficient(onNext, 1);
            getIdentifier.conjunction(notAlreadySet, set);
            getIdentifier.synapse.setCoefficient(unset, -1);
            val resolve = kb.new InvocationNode(kb.node(Bootstrap.resolve)).transform(kb.node(Common.name),
                getIdentifier);
            getIdentifier.then(resolve);
            {
              val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
                  .literal(kb.node(Common.name), resolvedIdentifier).transform(kb.node(Common.value), resolve);
              resolve.then(publish);
            }

            val remove = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
                .literal(kb.node(Common.name), resolvedIdentifier);
            remove.conjunction(onNext, unset);
          }

          val reference = new SynapticNode();
          indexNode(reference, "reference", parse, kb);
          {
            val ifAmp = kb.eavNode(true, false, kb.node(Common.value), kb.node((int) '&'));
            val set = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
                .literal(kb.node(Common.name), reference).literal(kb.node(Common.value), reference);
            set.conjunction(ifAmp, identifier.properties.get(kb.node("notStarted")));
            set.synapse.setCoefficient(kb.eavNode(true, true, state, identifier), -1);
            set.synapse.setCoefficient(kb.eavNode(true, true, state, reference), -1);

            val unset = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
                .literal(kb.node(Common.name), reference);
            unset.conjunction(onNext, kb.eavNode(true, false, state, identifier, null));
          }

          val assignment = new SynapticNode();
          indexNode(assignment, "assignment", parse, kb);
          {
            val ifEq = kb.eavNode(true, false, kb.node(Common.value), kb.node((int) '='));
            val startAssignment = new SynapticNode();
            startAssignment.conjunction(onNext, ifEq, kb.eavNode(true, true, state, resolvedIdentifier));

            val setProperty = kb.new InvocationNode(kb.node(BuiltIn.node)).literal(kb.node(Common.entrypoint),
                kb.node(BuiltIn.setProperty));
            setProperty.comment = "setProperty";
            {
              val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
                  .literal(kb.node(Common.name), setProperty).transform(kb.node(Common.value), setProperty);
              setProperty.then(publish);
            }
            startAssignment.then(setProperty);
            val literal = kb.new InvocationNode(kb.node(BuiltIn.node));
            setProperty.then(literal);
            val setLiteral = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
                .transform(kb.node(Common.object), setProperty).literal(kb.node(Common.name), kb.node(Common.literal))
                .transform(kb.node(Common.value), literal);
            literal.then(setLiteral);
            val consumeResolved = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
                .transform(kb.node(Common.object), state).literal(kb.node(Common.name), resolvedIdentifier);
            {
              val resetIdentifier = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
                  .transform(kb.node(Common.object), state).literal(kb.node(Common.name), identifier);
              consumeResolved.then(resetIdentifier);
            }
            setProperty.then(consumeResolved);
            val setName = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), literal)
                .literal(kb.node(Common.name), kb.node(Common.name)).transform(kb.node(Common.value), consumeResolved);
            setName.conjunction(setLiteral, consumeResolved);

            val rhs = new SynapticNode();
            val hadSetProperty = new SynapticNode();
            hadSetProperty.synapse.setCoefficient(kb.eavNode(true, true, state, setProperty), 1);
            hadSetProperty.synapse.setCoefficient(onNext, -1);
            rhs.conjunction(onNext, hadSetProperty, kb.eavNode(true, true, state, resolvedIdentifier));
            rhs.synapse.setCoefficient(kb.eavNode(true, false, state, resolvedIdentifier, null), -1);
            rhs.then(consumeResolved);

            val getSetProperty = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
                .transform(kb.node(Common.object), state).literal(kb.node(Common.name), setProperty);
            {
              val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
                  .literal(kb.node(Common.name), assignment).transform(kb.node(Common.value), getSetProperty);
              getSetProperty.then(publish);
              val resetSetProperty = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
                  .transform(kb.node(Common.object), state).literal(kb.node(Common.name), setProperty);
              getSetProperty.then(resetSetProperty);
            }
            rhs.then(getSetProperty);

            val isRef = kb.eavNode(true, false, state, reference, reference);

            val getLiteral = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
                .transform(kb.node(Common.object), getSetProperty)
                .literal(kb.node(Common.name), kb.node(Common.literal));
            val setLiteralValue = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
                .transform(kb.node(Common.object), getLiteral).literal(kb.node(Common.name), kb.node(Common.value))
                .transform(kb.node(Common.value), consumeResolved);
            getLiteral.conjunction(getSetProperty, isRef);
            setLiteralValue.conjunction(getLiteral, consumeResolved);

            val transform = kb.new InvocationNode(kb.node(BuiltIn.node));
            transform.synapse.setCoefficient(rhs, 1);
            transform.synapse.setCoefficient(isRef, -1);
            val setTransform = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
                .transform(kb.node(Common.object), getSetProperty)
                .literal(kb.node(Common.name), kb.node(Common.transform)).transform(kb.node(Common.value), transform);
            setTransform.conjunction(getSetProperty, transform);
            val setTransformValue = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
                .transform(kb.node(Common.object), transform).literal(kb.node(Common.name), kb.node(Common.value))
                .transform(kb.node(Common.value), consumeResolved);
            setTransformValue.conjunction(setTransform, consumeResolved);
          }
        }

        val collectResult = new SynapticNode();
        indexNode(collectResult, "collectResult", parse, kb);
        finalOnNext.then(collectResult);
        {
          val getAssignment = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
              .transform(kb.node(Common.object), state)
              .literal(kb.node(Common.name), parse.properties.get(kb.node("assignment")));
          collectResult.then(getAssignment);
          val getResolvedIdentifier = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
              .transform(kb.node(Common.object), state)
              .literal(kb.node(Common.name), parse.properties.get(kb.node("resolvedIdentifier")));
          collectResult.then(getResolvedIdentifier);

          val returnAssignment = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
              .literal(kb.node(Common.name), kb.node(Common.returnValue))
              .transform(kb.node(Common.value), getAssignment);
          getAssignment.then(returnAssignment);
          returnAssignment.synapse.setCoefficient(kb.eavNode(true, false, getAssignment, null), -1);

          val returnResolved = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
              .literal(kb.node(Common.name), kb.node(Common.returnValue))
              .transform(kb.node(Common.value), getResolvedIdentifier);
          returnResolved.conjunction(getResolvedIdentifier, getAssignment);
          returnResolved.synapse.setCoefficient(kb.eavNode(true, false, getResolvedIdentifier, null), -1);
        }
      }
    },
    /**
     * <li>{@link Common#value}: string to evaluate
     */
    eval {
      @Override
      protected void setUp(final KnowledgeBase kb, final Node eval) {
        val parse = kb.new InvocationNode(kb.node(Bootstrap.parse)).inherit(kb.node(Common.value));
        eval.then(parse);

        val parentContext = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
            .transform(kb.node(Common.object), kb.node(Common.caller))
            .literal(kb.node(Common.name), kb.node(Common.context));
        parse.then(parentContext);

        val activate = kb.new InvocationNode(kb.node(BuiltIn.activate)).transform(kb.node(Common.value), parse)
            .transform(kb.node(Common.context), parentContext);
        parentContext.then(activate);
      }
    };

    private static void indexNode(final Node node, final String name, final Node index, final KnowledgeBase kb) {
      node.comment = name;
      index.properties.put(kb.node(name), node);
    }

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
