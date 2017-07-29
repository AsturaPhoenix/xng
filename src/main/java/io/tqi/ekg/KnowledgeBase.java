package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collections;
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
import io.tqi.ekg.value.CharValue;
import io.tqi.ekg.value.ImmutableNodeList;
import io.tqi.ekg.value.ImmutableValue;
import io.tqi.ekg.value.NodeIterator;
import io.tqi.ekg.value.NodeList;
import io.tqi.ekg.value.NodeValue;
import io.tqi.ekg.value.NumericValue;
import io.tqi.ekg.value.StringValue;

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

		registerBuiltIn("activateTailNGrams", node -> {
			final Object arg = node.getValue();
			if (arg instanceof NodeList) {
				activateTailNGrams((NodeList) arg);
			}
		});

		registerBuiltIn("getProperty", args -> {
			final Node object = args.getProperty(getOrCreateNode("object")),
					property = args.getProperty(getOrCreateNode("property"));
			return object == null || property == null ? null : object.getProperty(property);
		}).setRefractory(0);

		// this can also be used to rename default return values, which are
		// normally put to the ARGUMENT property of the callback.
		registerBuiltIn("setProperty", args -> {
			final Node object = args.getProperty(getOrCreateNode("object")),
					property = args.getProperty(getOrCreateNode("property"));
			if (object != null && property != null) {
				object.setProperty(property, args.getProperty(ARGUMENT));
			}
		}).setRefractory(0);

		registerBuiltIn("math.add", node -> {
			final Node a = node.getProperty(arg(1)), b = node.getProperty(arg(2));
			if (a.getValue() instanceof NumericValue && b.getValue() instanceof NumericValue) {
				final Number an = ((NumericValue) a.getValue()).getValue(),
						bn = ((NumericValue) b.getValue()).getValue();
				return getOrCreateValueNode(new NumericValue(mathAdd(an, bn)));
			} else {
				return null;
			}
		});

		registerBuiltIn("print", node -> {
			final NodeValue arg = node.getValue();
			if (arg instanceof StringValue) {
				rxOutput.onNext(((StringValue) arg).getValue());
			}
		});

		registerBuiltIn("splitString", node -> {
			final NodeValue arg = node.getValue();
			if (arg instanceof StringValue) {
				// TODO(rosswang): thread-safe mutable
				final ImmutableNodeList split = ImmutableNodeList
						.from(((StringValue) arg).getValue().chars().mapToObj(c -> {
							return getOrCreateValueNode(new CharValue((char) c));
						}).collect(Collectors.toList()));
				return getOrCreateValueNode(split);
			}

			return null;
		});

		final Node iterForward = registerBuiltIn("iterator.forward",
				node -> updateIterator(node, ((NodeIterator) node.getValue()).next())),
				iterBack = registerBuiltIn("iterator.back",
						node -> updateIterator(node, ((NodeIterator) node.getValue()).previous()));

		registerBuiltIn("iterator", node -> {
			final NodeValue arg = node.getValue();
			if (arg instanceof NodeList) {
				final NodeIterator iterator = new NodeIterator((NodeList) arg);
				final Node iterNode = createNode(iterator);

				final Node iterForwardCall = createNode(), iterBackCall = createNode();
				iterForwardCall.setProperty(EXECUTE, iterForward);
				iterForwardCall.setProperty(ARGUMENT, iterNode);
				iterBackCall.setProperty(EXECUTE, iterBack);
				iterBackCall.setProperty(ARGUMENT, iterNode);

				iterNode.setProperty(getOrCreateNode("forward"), iterForwardCall);
				iterNode.setProperty(getOrCreateNode("back"), iterBackCall);

				final Node atStartProp = getOrCreateNode("atStart"), atEndProp = getOrCreateNode("atEnd"),
						onMoveProp = getOrCreateNode("onMove");
				iterNode.setProperty(atStartProp, createNode());
				iterNode.setProperty(atEndProp, createNode());
				iterNode.setProperty(onMoveProp, createNode());

				// TODO(rosswang): revisit whether we should have stateful
				// properties too

				return iterNode;
			}

			return null;
		});

		registerBuiltIn("toString", node -> {
			final NodeValue arg = node.getValue();
			return arg == null ? null
					: arg instanceof StringValue ? node : getOrCreateValueNode(new StringValue(arg.toString()));
		});
	}

	private void updateIterator(final Node iterNode, final Node current) {
		final NodeIterator iterator = (NodeIterator) iterNode.getValue();
		// Note that the atEnd and atStart property activations also result in
		// the atEnd and atStart globally indexed nodes being called with the
		// iterator as an argument.
		if (!iterator.hasNext()) {
			iterNode.getProperty(getOrCreateNode("atEnd")).activate();
		}
		if (!iterator.hasPrevious()) {
			iterNode.getProperty(getOrCreateNode("atStart")).activate();
		}
		final Node onMove = iterNode.getProperty(getOrCreateNode("onMove"));
		onMove.setProperty(ARGUMENT, current);
		current.activate();
		onMove.activate();
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

	private Number mathAdd(final Number a, final Number b) {
		if (a.doubleValue() != a.longValue() || b.doubleValue() != b.longValue()) {
			return a.doubleValue() + b.doubleValue();
		} else {
			return a.longValue() + b.longValue();
		}
	}

	/**
	 * Gets or creates a node representing a positional argument. These are
	 * typically property names under {@code ARGUMENT} nodes.
	 * 
	 * @param ordinal
	 *            one-based argument index
	 * @return
	 */
	public Node arg(final int ordinal) {
		return getOrCreateNode("arg_" + ordinal);
	}

	public Node getOrCreateNode(final String identifier) {
		return getOrCreateNode(new Identifier(identifier));
	}

	public Node getOrCreateNode(final Identifier identifier) {
		return internalGetOrCreateNode(identifier, null);
	}

	public Node getOrCreateValueNode(final ImmutableValue value) {
		return internalGetOrCreateNode(value, value);
	}

	public Node getOrCreateValueNode(final String value) {
		return getOrCreateValueNode(new StringValue(value));
	}

	public Node getOrCreateValueNode(final Number value) {
		return getOrCreateValueNode(new NumericValue(value));
	}

	private Node internalGetOrCreateNode(final Serializable label, final NodeValue value) {
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

	public Node createNode(final NodeValue value) {
		final Node node = new Node(value);
		initNode(node);
		nodes.add(node);
		return node;
	}

	public void indexNode(final String identifier, final Node node) {
		indexNode(new Identifier(identifier), node);
	}

	public void indexNode(final Identifier identifier, final Node node) {
		index.put(identifier, node);
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

	public void activateTailNGrams(final NodeList sequence) {
		ImmutableNodeList nGram = ImmutableNodeList.from(sequence);
		while (!nGram.isEmpty()) {
			getOrCreateValueNode(nGram).activate();
			nGram = new ImmutableNodeList(nGram.subList(1, nGram.size()));
		}
	}
}
