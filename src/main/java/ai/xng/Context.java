package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import io.reactivex.Observable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;

/**
 * A node value that signifies that a node is behaving as an
 * evaluation/execution context. Properties of such nodes are used as contextual
 * memory.
 * 
 * Although this class is {@link Serializable} for use as a node value, most of
 * its state is transient. That is, although contexts are nodes, activations are
 * not expected to remain valid across serialization.
 */
public class Context implements Serializable {
  private static final long serialVersionUID = 3575759895941990217L;

  private transient Map<Node, Node.ContextualState> nodeStates;
  private transient Map<Synapse, Synapse.ContextualState> synapseStates;

  private final NodeQueue activations = new NodeQueue(this);

  private transient Subject<Boolean> rxActive;
  private transient AtomicInteger refCount;

  // The node representing this context.
  public final Node node;

  // The node that constructed this context. This is used as the key where the
  // return value(s) will be stored in the parent context.
  public final Node invocation;

  /**
   * The exception handler for this context. This defaults to completing
   * {@link #continuation} exceptionally, unless overridden.
   */
  public transient Consumer<Exception> exceptionHandler;

  // A synchronous bridge for this context, completed when the return value is
  // first set or the context first
  // becomes idle, or completed exceptionally on unhandled exception.
  private transient CompletableFuture<Void> continuation;

  public CompletableFuture<Void> continuation() {
    return continuation;
  }

  public Context(final Function<Serializable, Node> nodeFactory, final Node invocation) {
    init();
    this.invocation = invocation;
    node = nodeFactory.apply(this);
  }

  public Context(final Function<Serializable, Node> nodeFactory) {
    this(nodeFactory, null);
  }

  private void init() {
    nodeStates = new ConcurrentHashMap<>();
    synapseStates = new ConcurrentHashMap<>();

    rxActive = BehaviorSubject.createDefault(false);
    refCount = new AtomicInteger();

    continuation = new CompletableFuture<>();
    exceptionHandler = continuation::completeExceptionally;
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    init();
    stream.defaultReadObject();
  }

  public Observable<Boolean> rxActive() {
    return rxActive;
  }

  public void addRef() {
    if (refCount.getAndIncrement() == 0) {
      rxActive.onNext(true);
    }
  }

  public void releaseRef() {
    if (refCount.decrementAndGet() == 0) {
      rxActive.onNext(false);
    }
  }

  /**
   * Gets the contextual state for the given node, creating if absent.
   */
  public Node.ContextualState nodeState(final Node node) {
    return nodeStates.computeIfAbsent(node, n -> {
      activations.add(node);
      return n.new ContextualState(this);
    });
  }

  /**
   * Gets the contextual state for the given synapse, creating if absent.
   */
  public Synapse.ContextualState synapseState(final Synapse synapse) {
    return synapseStates.computeIfAbsent(synapse, s -> s.new ContextualState(this));
  }

  public Node require(final Node property) {
    final Node value = node.properties.get(property);
    if (value == null) {
      throw new ContextException(this, property);
    }
    return value;
  }
}
