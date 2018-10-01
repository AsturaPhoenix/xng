package io.tqi.ekg;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class Context {
	public final Context parent;

	public final ObservableNodeMap index = new ObservableNodeMap();

	private final Map<Node, Node.ContextualState> nodeStates = new ConcurrentHashMap<>();
	private final Map<Synapse, Synapse.ContextualState> synapseStates = new ConcurrentHashMap<>();

	/**
	 * A {@link CompletableFuture} that signals when the context has been
	 * disposed and completes with its return value, if any. Contextual states
	 * should listen to this to know when to stop propagating.
	 */
	private final CompletableFuture<Node> lifetime = new CompletableFuture<>();

	public Context() {
		this(null);
	}

	public Context(final Context parent) {
		this.parent = parent;
		parent.lifetime().thenRun(() -> lifetime.complete(null));
	}

	public CompletableFuture<Node> lifetime() {
		return lifetime;
	}

	/**
	 * Gets the contextual state for the given node, creating if absent.
	 */
	public Node.ContextualState nodeState(final Node node) {
		return nodeStates.computeIfAbsent(node, n -> n.new ContextualState(this));
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
