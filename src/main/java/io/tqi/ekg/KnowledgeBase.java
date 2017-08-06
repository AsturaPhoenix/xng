package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;

public class KnowledgeBase implements Serializable, AutoCloseable {
    private static final long serialVersionUID = 4850129606513054849L;

    private final ConcurrentMap<Serializable, Node> index = new ConcurrentHashMap<>();
    private final Set<Node> nodes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private transient Subject<String> rxOutput;
    private transient Subject<Object> rxChange;

    public final Node EXECUTE = node("execute"), ARGUMENT = node("arg"), CALLBACK = node("callback"), CLASS = node(),
            OBJECT = node("object"), PROPERTY = node("property"), METHOD = node("method"), TRUE = node("true"),
            FALSE = node("false"), EXCEPTION = node("exception"), SOURCE = node("exception.source"),
            VALUE = node("value");

    public enum BuiltIn {
        /**
         * Takes two args: {@link KnowledgeBase#OBJECT} and "property".
         */
        getProperty {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                final Node object = node.getProperty(kb.OBJECT), property = node.getProperty(kb.PROPERTY);
                return object.getProperty(property);
            }
        },
        /**
         * A limited Java interop bridge. Method parameter types must be fully
         * and exactly specified via "paramN" properties on the argument node,
         * having values of Java classes. Arguments are passed as "argN"
         * properties on the argument node, and must be serializable. Missing
         * arguments are passed null.
         */
        invoke {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) throws Exception {
                final Node classNode = node.getProperty(kb.CLASS), objectNode = node.getProperty(kb.OBJECT),
                        methodNode = node.getProperty(kb.METHOD);
                final Object object;
                final Class<?> clazz;
                if (objectNode != null) {
                    object = objectNode.getValue();
                    clazz = object.getClass();

                    if (classNode != null && clazz != classNode.getValue()) {
                        throw new IllegalArgumentException("Provided class does not match object class");
                    }
                } else {
                    object = null;
                    clazz = (Class<?>) classNode.getValue();
                }

                ArrayList<Class<?>> params = new ArrayList<>();
                Node param;
                for (int i = 1; (param = node.getProperty(kb.param(i))) != null; i++) {
                    params.add((Class<?>) param.getValue());
                }

                Method method = clazz.getMethod((String) methodNode.getValue(), params.toArray(new Class<?>[0]));
                ArrayList<Object> args = new ArrayList<>();
                for (int i = 1; i <= params.size(); i++) {
                    Node arg = node.getProperty(kb.arg(i));
                    args.add(arg == null ? null : arg.getValue());
                }

                Object ret = method.invoke(object, args.toArray());
                if (method.getReturnType() != null) {
                    return kb.valueNode((Serializable) ret);
                } else {
                    return null;
                }
            }
        },
        javaClass {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) throws ClassNotFoundException {
                return kb.valueNode(Class.forName((String) node.getValue()));
            }
        },
        node {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                return kb.node();
            }
        },
        print {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                final Object arg = node.getValue();
                kb.rxOutput.onNext((String) arg);
                return null;
            }
        },
        /**
         * This can also be used to rename default return values, which are
         * normally put to the ARGUMENT property of the callback.
         * <p>
         * Args:
         * <ul>
         * <li>{@link KnowledgeBase#OBJECT}
         * <li>{@link KnowledgeBase#PROPERTY}
         * <li>{@link KnowledgeBase#VALUE}
         * </ul>
         */
        setProperty {
            @Override
            public Node impl(final KnowledgeBase kb, final Node node) {
                final Node object = node.getProperty(kb.OBJECT), property = node.getProperty(kb.PROPERTY),
                        value = node.getProperty(kb.VALUE);
                if (property == null) {
                    throw new NullPointerException();
                }
                object.setProperty(property, value);
                return null;
            }
        };

        public abstract Node impl(final KnowledgeBase kb, final Node node) throws Exception;
    }

    public KnowledgeBase() {
        init();
    }

    private void init() {
        rxOutput = PublishSubject.create();
        rxChange = PublishSubject.create();

        for (final Node node : nodes) {
            initNode(node);
        }

        for (final BuiltIn builtIn : BuiltIn.values()) {
            registerBuiltIn(builtIn);
        }
    }

    private void initNode(final Node node) {
        // fields may be initialized before kb subjects are, in which case kb
        // init will catch this
        if (rxChange != null) {
            node.rxActivate().subscribe(t -> {
                final Node rawFn = node.getProperty(EXECUTE);
                if (rawFn != null) {
                    invoke(rawFn, node.getProperty(ARGUMENT), node.getProperty(CALLBACK));
                }
            });
            node.rxChange().subscribe(rxChange);
        }
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

    /**
     * Gets or creates a node representing a positional argument. These are
     * typically property names under {@code ARGUMENT} nodes.
     * 
     * @param ordinal
     *            one-based argument index
     * @return
     */
    public Node arg(final int ordinal) {
        return node("arg" + ordinal);
    }

    /**
     * Gets or creates a node representing a positional parameter type, for use
     * with {@link BuiltIn#invoke} as property names under {@code ARGUMENT}
     * nodes.
     * 
     * @param ordinal
     *            one-based argument index
     * @return
     */
    public Node param(final int ordinal) {
        return node("param" + ordinal);
    }

    public Node node(final BuiltIn builtIn) {
        return node("BuiltIn." + builtIn.name());
    }

    public Node node(final String identifier) {
        return node(new Identifier(identifier));
    }

    public Node node(final Identifier identifier) {
        return getOrCreateNode(identifier, null);
    }

    public Node valueNode(final Serializable value) {
        final Node node = new Node(value);
        initNode(node);
        nodes.add(node);
        return node;
    }

    private Node getOrCreateNode(final Serializable label, final Serializable value) {
        return index.computeIfAbsent(label, x -> {
            final Node node = new Node(value);
            initNode(node);
            nodes.add(node);
            return node;
        });
    }

    // All kb nodes must be created through a node(...) or valueNode(...) method
    // to ensure the proper callbacks are set.
    public Node node() {
        final Node node = new Node();
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

    private Node registerBuiltIn(final BuiltIn builtIn) {
        // TODO(rosswang): Keep the original built-in impl node indexed separate
        // from the main index so that we can rebind the impl to the correct
        // node after deserialization.
        final Node node = node(builtIn);
        node.rxActivate().subscribe(t -> {
            final Node result;
            try {
                result = builtIn.impl(KnowledgeBase.this, node.getProperty(ARGUMENT));
            } catch (final Exception e) {
                Node eNode = valueNode(e);
                eNode.setProperty(SOURCE, node);
                EXCEPTION.setProperty(ARGUMENT, eNode);
                EXCEPTION.activate();
                return;
            }
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
}
