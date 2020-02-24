package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;

import io.reactivex.Observable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import lombok.val;

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
  // first set or the context first becomes idle, or completed exceptionally on
  // unhandled exception.
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

    activations.rxActivate().subscribe(node -> {
      final List<Node> recent;
      try (val lock = new DebugLock(activations.mutex())) {
        recent = Iterators.find(new HebbianReinforcementWindow(Optional.empty()), deck -> deck.get(0) == node);
      }
      hebbianReinforcement(recent, Optional.empty(), HEBBIAN_IMPLICIT_WEIGHT);
    });
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    stream.defaultReadObject();
    init();
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

  public Lock mutex() {
    return activations.mutex();
  }

  public void reinforce(final Optional<Long> time, final Optional<Long> decayPeriod, final float weight) {
    try (val lock = new DebugLock(activations.mutex())) {
      for (final HebbianReinforcementWindow window = new HebbianReinforcementWindow(time); window.hasNext();) {
        hebbianReinforcement(window.next(), time, weight * HEBBIAN_EXPLICIT_WEIGHT_FACTOR);
      }
    }

    // There are definitely more efficient ways to do this.
    for (final Synapse.ContextualState synapseState : synapseStates.values()) {
      synapseState.reinforce(time, decayPeriod, weight);
    }
  }

  public static long HEBBIAN_MAX_GLOBAL = 15000, HEBBIAN_MAX_LOCAL = 500;
  public static float HEBBIAN_IMPLICIT_WEIGHT = .10f, HEBBIAN_EXPLICIT_WEIGHT_FACTOR = .01f;

  private class HebbianReinforcementWindow implements Iterator<List<Node>> {
    final Optional<Long> time;
    final Iterator<Node> activations = Context.this.activations.iterator();
    Node next;
    final Queue<Node> deck = new ArrayDeque<>();

    HebbianReinforcementWindow(final Optional<Long> time) {
      this.time = time;
      do {
        draw();
        // Drain until we reach the effective time.
      } while (next != null && time.isPresent() && next.getLastActivation(Context.this) > time.get());
    }

    private void draw() {
      if (activations.hasNext()) {
        next = activations.next();
        final long lastActivation = next.getLastActivation(Context.this);
        if (time.isPresent() && lastActivation < time.get() - HEBBIAN_MAX_GLOBAL || lastActivation == 0) {
          next = null;
        }
      } else {
        next = null;
      }
    }

    @Override
    public boolean hasNext() {
      return next != null || !deck.isEmpty();
    }

    @Override
    public List<Node> next() {
      while (next != null && (deck.isEmpty()
          || next.getLastActivation(Context.this) >= deck.peek().getLastActivation(Context.this) - HEBBIAN_MAX_LOCAL)) {
        deck.add(next);
        draw();
      }

      final List<Node> snapshot = ImmutableList.copyOf(deck);

      deck.remove();

      return snapshot;
    }
  }

  private void hebbianReinforcement(final List<Node> snapshot, final Optional<Long> time, final float baseWeight) {
    final Node posterior = snapshot.get(0);
    final long posteriorTime = posterior.getLastActivation(this);
    final float posteriorWeight = time.isPresent()
        ? baseWeight * (1 - (float) (time.get() - posteriorTime) / HEBBIAN_MAX_GLOBAL)
        : baseWeight;

    for (final Node prior : snapshot.subList(1, snapshot.size())) {
      Synapse.Evaluation priorEvaluation = posterior.synapse.getPrecedingEvaluation(this, prior, posteriorTime);
      assert priorEvaluation == null || priorEvaluation.time > 0;

      if (priorEvaluation == null) {
        // New connection; treat as a prior evaluation of 0.
        priorEvaluation = new Synapse.Evaluation(prior.getLastActivation(this), 0);
        // TODO: Also add initial inertia based on node activation history. For now this
        // is just 1 at 0.
      }

      final float weight = posteriorWeight * (1 - (float) (posteriorTime - priorEvaluation.time) / HEBBIAN_MAX_LOCAL);
      final float target = 1 - posterior.synapse.getValue(this, priorEvaluation.time) + priorEvaluation.value;

      posterior.synapse.profile(prior).getCoefficient().add(target, weight);
    }
  }
}
