package ai.xng;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Observable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;

public class Context {
  public final Context parent;

  public final ConcurrentMap<Node, Node> index = new ConcurrentHashMap<Node, Node>();

  private final Map<Node, Node.ContextualState> nodeStates = new ConcurrentHashMap<>();
  private final Map<Synapse, Synapse.ContextualState> synapseStates = new ConcurrentHashMap<>();

  private final NodeQueue activations = new NodeQueue(this);

  /**
   * A {@link CompletableFuture} that signals when the context has been disposed
   * and completes with its return value, if any. Contextual states should listen
   * to this to know when to stop propagating.
   */
  private final CompletableFuture<Node> lifetime = new CompletableFuture<>();

  private final Subject<Boolean> rxActive = BehaviorSubject.createDefault(false);
  private final AtomicInteger refCount = new AtomicInteger();

  public Context() {
    this(null);
  }

  public Context(final Context parent) {
    this.parent = parent;
    if (parent != null) {
      parent.lifetime().thenRun(() -> lifetime.complete(null));
    }
  }

  public CompletableFuture<Node> lifetime() {
    return lifetime;
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

  public void close() {
    close((Node) null);
  }

  public void close(Node value) {
    lifetime.complete(value);
  }

  public void close(Throwable t) {
    lifetime.completeExceptionally(t);
  }

  public Node require(final Node key) {
    final Node value = index.get(key);
    if (value == null) {
      throw new ContextException(this, key);
    }
    return value;
  }
}
