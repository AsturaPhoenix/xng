// package ai.xng;

// import com.google.common.collect.ImmutableMap;

// /**
// * Exception thrown when a call has been made under a context missing a
// required
// * key.
// */
// public class ContextException extends IllegalArgumentException {
// private static final long serialVersionUID = 6398651175390307056L;

// public final ImmutableMap<Node, Node> properties;
// public final Node missingProperty;

// public ContextException(final Context context, final Node missingProperty) {
// super(String.format("Context missing required property %s. Properties: %s",
// missingProperty,
// context.node.properties));
// this.properties = ImmutableMap.copyOf(context.node.properties);
// this.missingProperty = missingProperty;
// }
// }
