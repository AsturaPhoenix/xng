package io.tqi.ekg;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectInputStream.GetField;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.base.Strings;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import javafx.geometry.Point3D;
import lombok.RequiredArgsConstructor;

public class KnowledgeBase implements Serializable, AutoCloseable, Iterable<Node>, ChangeObservable<Object> {
    private static final long serialVersionUID = -6461427806563494150L;

    @RequiredArgsConstructor
    private static class IdentityKey implements Serializable {
        private static final long serialVersionUID = -2428169144581856842L;

        final Serializable value;

        @Override
        public int hashCode() {
            return System.identityHashCode(value);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof IdentityKey && value == ((IdentityKey) obj).value;
        }
    }

    private NodeValueMap<String> index = new NodeValueMap<>();
    private NodeValueMap<IdentityKey> valueIndex = new NodeValueMap<>();

    private transient NodeQueue nodes = new NodeQueue();
    private transient NodePhysics physics;

    private transient Subject<String> rxOutput;
    private transient Subject<Node> rxNodeAdded;
    private transient Subject<Object> rxChange;

    public enum Common {
        context, transform, javaClass(
                "class"), object, property, name, exception, source, destination, value, coefficient, refractory, nodeCreated(
                        "node created"), nullNode("null");

        public final String identifier;

        private Common() {
            identifier = name();
        }

        private Common(final String identifier) {
            this.identifier = identifier;
        }
    }

    public enum BuiltIn {
        activate {
            @Override
            public void impl(final KnowledgeBase kb) {
                kb.context().get(kb.node(Common.value)).activate();
            }
        },
        clearProperties {
            @Override
            public void impl(final KnowledgeBase kb) {
                kb.context().get(kb.node(Common.object)).properties().clear();
            }
        },
        delete {
            @Override
            public void impl(final KnowledgeBase kb) {
                kb.context().get(kb.node(Common.object)).delete();
            }
        },
        /**
         * Gets a static or instance Java field.
         */
        field {
            @Override
            public void impl(final KnowledgeBase kb) throws Exception {
                final Node classNode = kb.context().get(kb.node(Common.javaClass)),
                        objectNode = kb.context().get(kb.node(Common.object)),
                        fieldNode = kb.context().get(kb.node(Common.name));
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

                Object ret = field.get(object);
                kb.context().put(kb.node(Common.value), kb.valueNode((Serializable) ret));
            }
        },
        /**
         * Takes two args: {@link Common#object} and {@link Common#property}.
         */
        getProperty {
            @Override
            public void impl(final KnowledgeBase kb) {
                final Node object = kb.context().get(kb.node(Common.object)),
                        property = kb.context().get(kb.node(Common.property));
                kb.context().put(kb.node(Common.value), object.properties().get(property));
            }
        },
        /**
         * A limited Java interop bridge. Method parameter types must be fully
         * and exactly specified via "paramN" index entries, null-terminated,
         * having values of Java classes. Arguments are passed as "argN" index
         * entries, and must be serializable. Missing arguments are passed null.
         */
        method {
            @Override
            public void impl(final KnowledgeBase kb) throws Exception {
                final Node classNode = kb.context().get(kb.node(Common.javaClass)),
                        objectNode = kb.context().get(kb.node(Common.object)),
                        methodNode = kb.context().get(kb.node(Common.name));
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
                for (int i = 1; (param = kb.context().get(kb.param(i))) != null; i++) {
                    params.add((Class<?>) param.getValue());
                }

                Method method = clazz.getMethod((String) methodNode.getValue(), params.toArray(new Class<?>[0]));
                ArrayList<Object> args = new ArrayList<>();
                for (int i = 1; i <= params.size(); i++) {
                    Node arg = kb.context().get(kb.arg(i));
                    args.add(arg == null ? null : arg.getValue());
                }

                Object ret = method.invoke(object, args.toArray());
                if (method.getReturnType() != null) {
                    kb.context().put(kb.node(Common.value), kb.valueNode((Serializable) ret));
                }
            }
        },
        findClass {
            @Override
            public void impl(final KnowledgeBase kb) throws ClassNotFoundException {
                kb.context().put(kb.node(Common.javaClass),
                        kb.valueNode(Class.forName((String) kb.context().get(kb.node(Common.name)).getValue())));
            }
        },
        createNode {
            @Override
            public void impl(final KnowledgeBase kb) {
                kb.node();
            }
        },
        print {
            @Override
            public void impl(final KnowledgeBase kb) {
                kb.rxOutput.onNext(Objects.toString(kb.context().get(kb.node(Common.value)).getValue()));
            }
        },
        setCoefficient {
            @Override
            public void impl(final KnowledgeBase kb) {
                final Node dest = kb.context().get(kb.node(Common.destination));
                dest.getSynapse().setCoefficient(kb.context().get(kb.node(Common.source)),
                        ((Number) kb.context().get(kb.node(Common.coefficient)).getValue()).floatValue());
            }
        },
        setProperty {
            @Override
            public void impl(final KnowledgeBase kb) {
                final Node object = kb.context().get(kb.node(Common.object)),
                        property = kb.context().get(kb.node(Common.property)),
                        value = kb.context().get(kb.node(Common.value));
                if (property == null) {
                    throw new NullPointerException();
                }
                object.properties().put(property, value);
            }
        },
        setRefractory {
            @Override
            public void impl(final KnowledgeBase kb) {
                kb.context().get(kb.node(Common.value))
                        .setRefractory(((Number) kb.context().get(kb.node(Common.refractory)).getValue()).longValue());
            }
        };

        public abstract void impl(final KnowledgeBase kb) throws Exception;
    }

    public KnowledgeBase() {
        init();
    }

    private void init() {
        rxOutput = PublishSubject.create();
        rxChange = PublishSubject.create();
        physics = new NodePhysics();

        for (final Node node : nodes) {
            initNode(node);
        }

        // create any new Common nodes
        for (final Common common : Common.values()) {
            node(common);
        }

        for (final BuiltIn builtIn : BuiltIn.values()) {
            registerBuiltIn(builtIn);
        }

        final TreeMap<String, Node> sortedIndex = new TreeMap<>();

        for (final Entry<String, Node> entry : index.entrySet()) {
            if (Strings.isNullOrEmpty(entry.getKey()))
                continue;

            final Node node = entry.getValue();

            if (node.getComment() == null) {
                node.setComment(entry.getKey());
            }

            if (node.getLocation() == null) {
                sortedIndex.put(entry.getKey(), node);
            }
        }

        int x = 0;
        for (final Node node : sortedIndex.values()) {
            node.setLocation(new Point3D(x++, 0, 0));
        }

        rxNodeAdded = PublishSubject.create();
        rxNodeAdded.subscribe(n -> {
            final Node nnn = node(Common.nodeCreated);
            context().put(nnn, n);
            nnn.activate();
        });
    }

    private void initNode(final Node node) {
        // fields may be initialized before kb subjects are, in which case kb
        // init will catch this
        if (rxChange != null) {
            node.setOnActivate(() -> mutateIndex(node));
            node.rxChange().subscribe(rxChange::onNext);
            physics.add(node);
        }

        if (rxNodeAdded != null) {
            rxNodeAdded.onNext(node);
        }
    }

    private void mutateIndex(final Node node) {
        final Node contextCopy = node.properties().get(node(Common.context));
        if (contextCopy != null) {
            for (final Entry<Node, Node> prop : contextCopy.properties().entrySet()) {
                if (prop.getValue() == node(Common.nullNode)) {
                    context().remove(prop.getKey());
                } else {
                    context().put(prop.getKey(), prop.getValue());
                }
            }
        }
        final Node transform = node.properties().get(node(Common.transform));
        if (transform != null) {
            for (final Entry<Node, Node> prop : transform.properties().entrySet()) {
                context().put(prop.getKey(), context().get(prop.getValue()));
            }
        }
    }

    private Node registerBuiltIn(final BuiltIn builtIn) {
        // TODO(rosswang): Keep the original built-in impl node indexed separate
        // from the main index so that we can rebind the impl to the correct
        // node after deserialization.
        final Node node = node(builtIn);
        node.setOnActivate(() -> {
            mutateIndex(node);
            try {
                builtIn.impl(this);
            } catch (final Exception e) {
                final Node exceptionNode = node(Common.exception);
                context().put(exceptionNode, valueNode(e).properties().put(node(Common.source), node));
                exceptionNode.activate();
            }
        });
        node.setRefractory(0);
        return node;
    }

    private void writeObject(final ObjectOutputStream o) throws IOException {
        o.defaultWriteObject();
        final Set<Node> serNodes = new HashSet<>();
        for (final Node node : nodes) {
            serNodes.add(node);
        }
        o.writeObject(serNodes);
    }

    @SuppressWarnings("unchecked")
    private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
        final GetField fields = stream.readFields();
        index = new NodeValueMap<>();
        index.putAll((Map<String, Node>) fields.get("index", new HashMap<>()));
        valueIndex = new NodeValueMap<>();
        valueIndex.putAll((Map<IdentityKey, Node>) fields.get("valueIndex", new HashMap<>()));
        try {
            final Map<Node, Node> legacyContext = (Map<Node, Node>) fields.get("context", new HashMap<>());
            final Node context = node(Common.context);
            if (legacyContext != null) {
                for (final Entry<Node, Node> entry : legacyContext.entrySet()) {
                    context.properties().put(entry.getKey(), entry.getValue());
                }
            }
        } catch (final IllegalArgumentException e) {
        }

        final Set<Node> serNodes = (Set<Node>) stream.readObject();
        int oldSize = serNodes.size();
        serNodes.addAll(index.values());
        if (serNodes.size() > oldSize) {
            System.out.println(
                    "WARNING: Serialized node set dropped at least " + (serNodes.size() - oldSize) + " nodes.");
        }

        nodes = new NodeQueue();
        nodes.addAll(serNodes);

        init();
    }

    @Override
    public void close() {
        rxOutput.onComplete();
        rxNodeAdded.onComplete();
        rxChange.onComplete();
    }

    public Observable<Node> rxNodeAdded() {
        return rxNodeAdded;
    }

    public Observable<String> rxOutput() {
        return rxOutput;
    }

    @Override
    public Observable<Object> rxChange() {
        return rxChange;
    }

    /**
     * Gets or creates a node representing a positional argument. These are
     * typically property names under {@code ARGUMENT} nodes.
     * 
     * @param ordinal
     *            one-based argument index
     * @return "arg<i>n</i>"
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
     * @return "param<i>n</i>"
     */
    public Node param(final int ordinal) {
        return node("param" + ordinal);
    }

    public Node node(final Common common) {
        return node(common.identifier);
    }

    public Node node(final BuiltIn builtIn) {
        return node("BuiltIn." + builtIn.name());
    }

    public Node node(final String identifier) {
        final boolean[] created = new boolean[1];
        final Node node = index.computeIfAbsent(identifier, x -> {
            final Node newNode = new Node();
            if (identifier != null)
                newNode.setComment(identifier);
            created[0] = true;
            nodes.add(newNode);
            return newNode;
        });
        if (created[0])
            initNode(node);
        return node;
    }

    @SuppressWarnings("unchecked")
    public Node valueNode(final Serializable value) {
        // Single-instance resolution for immutable types. Skip for/bootstrap
        // with Class since that's always single-instance.
        Serializable resolvingValue = value;
        if (!(value instanceof Class<?>)) {
            final Node values = valueNode(value.getClass()).properties().get(node(Common.value));
            if (values != null && values.getValue() instanceof ConcurrentHashMap) {
                resolvingValue = ((ConcurrentHashMap<Serializable, Serializable>) values.getValue())
                        .computeIfAbsent(value, x -> value);
            }
        }

        final Serializable resolvedValue = resolvingValue;

        return valueIndex.computeIfAbsent(new IdentityKey(resolvedValue), x -> {
            final Node node = new Node(resolvedValue);
            initNode(node);
            nodes.add(node);
            return node;
        });
    }

    public Node getNode(final String key) {
        return index.get(key);
    }

    // All kb nodes must be created through a node(...) or valueNode(...) method
    // to ensure the proper callbacks are set.
    public Node node() {
        final Node node = new Node();
        initNode(node);
        nodes.add(node);
        return node;
    }

    public ObservableNodeMap context() {
        return node(Common.context).properties();
    }

    @Override
    public Iterator<Node> iterator() {
        return nodes.iterator();
    }
}
