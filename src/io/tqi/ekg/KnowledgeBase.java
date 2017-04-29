package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import io.tqi.ekg.value.NodeIterator;

public class KnowledgeBase implements Serializable, AutoCloseable {
	private static final long serialVersionUID = 4850129606513054849L;

	private final ConcurrentMap<Serializable, Node> index = new ConcurrentHashMap<>();
	private final Set<Node> nodes = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private transient Subject<String> rxOutput;
	private transient Subject<Object> rxChange;

	public final Node EXECUTE = new Node(), ARGUMENT = new Node(), CALLBACK = new Node();

	public KnowledgeBase() {
		init();
	}

	private void init() {
		rxOutput = PublishSubject.create();
		rxChange = PublishSubject.create();

		for (final Node node : nodes) {
			initNode(node);
		}

		registerBuiltIn("getProperty", args -> {
			final Node object = args.getProperty(getOrCreateNode("object")),
					property = args.getProperty(getOrCreateNode("property"));
			return object == null || property == null ? null : object.getProperty(property);
		}).setRefractory(0);

		registerBuiltIn("splitString", node -> {
			final Object arg = node.getValue();
			if (arg instanceof String) {
				// TODO(rosswang): thread-safe mutable
				final ArrayList<Node> split = ((String) arg).chars().mapToObj(c -> {
					// TODO(rosswang): standardize node types
					return getOrCreateValueNode(Character.valueOf((char) c));
				}).collect(Collectors.toCollection(ArrayList::new));
				return getOrCreateValueNode(split);
			}

			return null;
		});

		registerBuiltIn("windowedIterator", node -> {
			final Object arg = node.getValue();
			if (arg instanceof List) {
				// TODO(rosswang): standardize node types
				@SuppressWarnings("unchecked")
				final ArrayList<Node> backing = (ArrayList<Node>) arg;
				final NodeIterator iterator = new NodeIterator(backing);
				final Node iterNode = new Node(iterator);

				final Node forward = new Node(null), back = new Node(null);
				iterNode.setProperty(getOrCreateNode("forward"), forward);
				iterNode.setProperty(getOrCreateNode("back"), back);

				final Node atStartProp = getOrCreateNode("atStart"), atEndProp = getOrCreateNode("atEnd"),
						onMoveProp = getOrCreateNode("onMove");
				iterNode.setProperty(atStartProp, new Node(null));
				iterNode.setProperty(atEndProp, new Node(null));
				iterNode.setProperty(onMoveProp, new Node(null));

				// TODO(rosswang): revisit whether we should have stateful
				// properties
				// too

				final Consumer<Node> update = current -> {
					// Note that the atEnd and atStart property activations also
					// result in
					// the atEnd and atStart globally indexed nodes being called
					// with the
					// iterator as an argument.
					if (!iterator.hasNext()) {
						iterNode.getProperty(atEndProp).activate();
					}
					if (!iterator.hasPrevious()) {
						iterNode.getProperty(atStartProp).activate();
					}
					final Node onMove = iterNode.getProperty(onMoveProp);
					onMove.setProperty(ARGUMENT, current);
					current.activate();
					onMove.activate();
				};

				forward.rxActivate().subscribe(t -> {
					update.accept(iterator.next());
				});

				back.rxActivate().subscribe(t -> {
					update.accept(iterator.previous());
				});

				return iterNode;
			}

			return null;
		});

		registerBuiltIn("activateTailNGrams", node -> {
			final Object arg = node.getValue();
			if (arg instanceof ArrayList) {
				@SuppressWarnings("unchecked")
				final ArrayList<Object> listArg = (ArrayList<Object>) arg;
				activateTailNGrams(listArg);
			} else if (arg instanceof Collection) {
				@SuppressWarnings("unchecked")
				final Collection<Object> collectionArg = (Collection<Object>) arg;
				activateTailNGrams(new ArrayList<>(collectionArg));
			}
		});

		registerBuiltIn("print", node -> {
			final Object arg = node.getValue();
			if (arg instanceof String) {
				rxOutput.onNext((String) arg);
			}
		});
	}

	private void initNode(final Node node) {
		node.rxActivate().subscribe(t -> {
			final Node rawFn = node.getProperty(EXECUTE);
			if (rawFn != null) {
				invoke(rawFn, node.getProperty(ARGUMENT), node.getProperty(CALLBACK));
			}
		});
		node.rxChange().subscribe(rxChange::onNext);
	}

	private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
		stream.defaultReadObject();
		init();
	}

	@Override
	public void close() {
		rxOutput.onComplete();
		rxChange.onComplete();
	}

	public Observable<String> rxOutput() {
		return rxOutput;
	}

	public Observable<Object> rxChange() {
		return rxChange;
	}

	public Node getOrCreateNode(final String identifier) {
		return getOrCreateNode(new Identifier(identifier));
	}

	public Node getOrCreateNode(final Identifier identifier) {
		return internalGetOrCreateNode(identifier, null);
	}

	public Node getOrCreateValueNode(final Serializable value) {
		return internalGetOrCreateNode(value, value);
	}

	private Node internalGetOrCreateNode(final Serializable label, final Serializable value) {
		return index.computeIfAbsent(label, x -> {
			final Node node = new Node(value);
			initNode(node);
			nodes.add(node);
			return node;
		});
	}

	// All kb nodes must be created through createNode or a getOrCreate method
	// to ensure the proper callbacks are set.
	public Node createNode() {
		final Node node = new Node();
		initNode(node);
		nodes.add(node);
		return node;
	}

	public void invoke(final Node rawFn, final Node rawArg, final Node rawCallback) {
		final Observable<Optional<Node>> resolvedFn = evaluate(rawFn), resolvedArg = evaluate(rawArg),
				resolvedCallback = evaluate(rawCallback);

		resolvedArg.subscribe(optArg -> {
			resolvedFn.subscribe(optFn -> optFn.ifPresent(fn -> {
				fn.setProperty(ARGUMENT, optArg);
				// use setObservableCallback rather than setting the callback
				// property directly to allow multicast of each single fn exec
				// to all callbacks
				setObservableCallback(fn).subscribe(
						optResult -> resolvedCallback.subscribe(optCallback -> optCallback.ifPresent(callback -> {
							invoke(callback, optResult.orElse(null), null);
						})));
				fn.activate();
			}));
		});
	}

	private Observable<Optional<Node>> evaluate(final Node node) {
		if (node == null) {
			return Observable.just(Optional.empty());
		}

		final Node fn = node.getProperty(EXECUTE);
		if (fn == null) {
			return Observable.just(Optional.of(node));
		} else {
			final Observable<Optional<Node>> results = setObservableCallback(node).replay().autoConnect(0);
			node.activate();
			return results;
		}
	}

	private Observable<Optional<Node>> setObservableCallback(final Node routine) {
		final Node callback = new Node();
		routine.setProperty(CALLBACK, callback);
		return callback.rxActivate().map(t -> Optional.ofNullable(callback.getProperty(ARGUMENT)));
	}

	public Node registerBuiltIn(final String name, final Consumer<Node> impl) {
		return registerBuiltIn(name, arg -> {
			impl.accept(arg);
			return null;
		});
	}

	public Node registerBuiltIn(final String name, final UnaryOperator<Node> impl) {
		final Node node = getOrCreateNode(name);
		node.rxActivate().subscribe(t -> {
			final Node result = impl.apply(node.getProperty(ARGUMENT));
			// do this raw rather than call invoke because ARGUMENT and CALLBACK
			// here should already have been evaluated by the invoke call for
			// the built-in
			final Node callback = node.getProperty(CALLBACK);
			if (callback != null) {
				callback.setProperty(ARGUMENT, result);
				callback.activate();
			}
		});
		return node;
	}

	public void activateTailNGrams(ArrayList<Object> sequence) {
		while (!sequence.isEmpty()) {
			getOrCreateValueNode(sequence).activate();
			sequence = new ArrayList<>(sequence.subList(1, sequence.size()));
		}
	}
}
