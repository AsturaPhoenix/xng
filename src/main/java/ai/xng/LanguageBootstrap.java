// package ai.xng;

// import java.util.Map;

// import com.google.common.collect.ImmutableMap;

// import ai.xng.KnowledgeBase.Bootstrap;
// import ai.xng.KnowledgeBase.BuiltIn;
// import ai.xng.KnowledgeBase.Common;
// import ai.xng.KnowledgeBase.InvocationNode;
// import ai.xng.KnowledgeBase.Ordinal;
// import lombok.RequiredArgsConstructor;
// import lombok.val;

// @RequiredArgsConstructor
// public class LanguageBootstrap {
// private final KnowledgeBase kb;

// public void indexNode(final Node index, final Node node, final String name) {
// kb.indexNode(index, node, name);
// }

// public Node parse(final String string, final Node literals, final Node
// transforms) {
// try {
// val context = kb.newContext();
// context.fatalOnExceptions();

// // Although naively we could activate parse directly, the easiest way to
// extract
// // a return value from a tail recursed subroutine is through an invocation
// node.
// val parse = kb.new InvocationNode(Bootstrap.parse)
// .literal(Common.value, string);
// if (literals != null)
// parse.literal(Common.literal, literals);
// if (transforms != null)
// parse.literal(Common.transform, transforms);
// parse.activate(context);
// context.blockUntilIdle();

// return context.node.properties.get(parse);
// } catch (final RuntimeException e) {
// throw new IllegalArgumentException(String.format("Failed to parse \"%s\"",
// string), e);
// }
// }

// public Node parse(final String string) {
// return parse(string, null, null);
// }

// public Node tail(final Node expression) {
// return expression.properties
// .get(kb.node(Bootstrap.parse).properties.get(kb.node("expression")).properties.get(kb.node("tail")));
// }

// /**
// * This differs from {@link Bootstrap#eval} in that it can capture the
// * evaluation result. This is not straightforward in {@code Bootstrap.eval}
// * because evaluation occurs in a foreign (parent) context.
// */
// public Node eval(final String string, final Node literals, final Node
// transforms) {
// try {
// val parsed = parse(string, literals, transforms);

// val context = kb.newContext();
// context.fatalOnExceptions();

// parsed.activate(context);
// context.blockUntilIdle();

// return context.node.properties.get(tail(parsed));
// } catch (final RuntimeException e) {
// throw new IllegalArgumentException(String.format("Failed to evaluate \"%s\"",
// string), e);
// }
// }

// public Node eval(final String string) {
// return eval(string, null, null);
// }

// private Node symbol(final int ordinal) {
// return kb.node(new Ordinal(kb.node(Common.symbol), ordinal));
// }

// private Node value(final int ordinal) {
// return kb.node(new Ordinal(kb.node(Common.value), ordinal));
// }

// private InvocationNode rewrite(final int length) {
// val rewrite = kb.new InvocationNode(BuiltIn.rewrite)
// .literal(Common.rewriteLength, length)
// .inherit(Common.rewriteWindow);
// rewrite.then(kb.new InvocationNode(BuiltIn.setProperty)
// .literal(Common.name, Common.returnValue)
// .transform(Common.value, rewrite));
// return rewrite;
// }

// private InvocationNode rewrite(final int length, final Node symbol, final
// Node value) {
// return rewrite(length)
// .literal(Common.symbol, symbol)
// .transform(Common.value, value);
// }

// private void mark(final Node symbol, final Node attribute) {
// symbol.properties.put(attribute, attribute);
// }

// /**
// * Expand eval/parse into a self-sustaining language.
// */
// public void bootstrap() {
// val process = kb.node(Bootstrap.processRewrite);

// // Most things smaller than a statement ultimately becomes an expression or a
// // literal.
// val parseValue = new SynapticNode();
// indexNode(process, parseValue, "value");

// // A common symbol/attribute for subroutines that can be activated to produce
// an
// // evaluated value. These nodes should have expressionTail properties.
// val expression = new SynapticNode();
// indexNode(process, expression, "expression");
// mark(expression, expression);
// mark(expression, parseValue);

// // A common symbol for nodes that are provided as-is. These are generally
// passed
// // as literals.
// val literal = new SynapticNode();
// indexNode(process, literal, "literal");
// mark(literal, parseValue);

// val classifyCodePoint = new SynapticNode();
// classifyCodePoint.conjunction(process, kb.eavNode(true, false, symbol(0),
// kb.node(Common.codePoint)));

// val identifierStart = new SynapticNode();
// indexNode(process, identifierStart, "identifierStart");
// classifyCodePoint
// .then(kb.new InvocationNode(BuiltIn.method)
// .literal(Common.javaClass, Character.class)
// .literal(Common.name, "isJavaIdentifierStart")
// .literal(kb.param(0), int.class)
// .transform(kb.arg(0), value(0)))
// .then(rewrite(1, identifierStart, value(0)));

// // val isIdentifierPart = kb.new InvocationNode(BuiltIn.method)
// // .literal(Common.javaClass, Character.class)
// // .literal(Common.name, "isJavaIdentifierPart")
// // .literal(kb.param(0), int.class)
// // .transform(kb.arg(0), value(0));
// // indexNode(process, isIdentifierPart, "isIdentifierPart");
// // isCodePoint.then(isIdentifierPart);

// // val isWhitespace = kb.new InvocationNode(BuiltIn.method)
// // .literal(Common.javaClass, Character.class)
// // .literal(Common.name, "isWhitespace")
// // .literal(kb.param(0), int.class)
// // .transform(kb.arg(0), value(0));
// // indexNode(process, isWhitespace, "isWhitespace");
// // isCodePoint.then(isWhitespace);

// // val identifier = new SynapticNode();
// // indexNode(process, identifier, "identifier");
// // {
// // val buffer = new SynapticNode();
// // indexNode(identifier, buffer, "buffer");

// // val hasBuffer = kb.eavNode(true, false, symbol(-1), buffer);

// // val start = new SynapticNode();
// // indexNode(identifier, start, "start");
// // {
// // isIdentifierStart.inhibitor(hasBuffer);

// // kb.eavNode(true, false, isIdentifierStart, kb.node(true))
// // .then(start);

// // // A word of warning: mutable buffer folding may not work if this parse
// // branch
// // // ever goes nondeterministic.
// // val newBuffer = kb.new InvocationNode(Bootstrap.newInstance)
// // .literal(Common.javaClass, StringBuilder.class);
// // newBuffer.comment = "newBuffer";
// // start.then(newBuffer);

// // val append = kb.new InvocationNode(BuiltIn.method)
// // .transform(Common.object, newBuffer)
// // .literal(Common.name, "appendCodePoint")
// // .literal(kb.param(0), int.class)
// // .transform(kb.arg(0), value(0));
// // newBuffer.then(append);
// // append.then(rewrite(1, buffer, newBuffer));
// // }

// // val part = new SynapticNode();
// // indexNode(identifier, part, "part");
// // {
// // part.conjunction(hasBuffer, kb.eavNode(true, false, isIdentifierPart,
// // kb.node(true)));

// // val append = kb.new InvocationNode(BuiltIn.method)
// // .transform(Common.object, value(-1))
// // .literal(Common.name, "appendCodePoint")
// // .literal(kb.param(0), int.class)
// // .transform(kb.arg(0), value(0));
// // part.then(append);

// // // Simply delete the trailing code point now that we've folded it into the
// // // buffer.
// // part.then(rewrite(1));
// // }

// // val end = new SynapticNode();
// // indexNode(identifier, end, "end");
// // {
// // end.conjunction(process, kb.eavNode(true, false, symbol(0), buffer))
// // // We can get away with this simple inhibition because any non-identifier
// // // character coming up will be consumed as whitespace or an operator,
// after
// // // which we'll backtrack to here.
// // .inhibitor(kb.eavNode(true, false, symbol(1), kb.node(Common.codePoint)));

// // val toString = kb.new InvocationNode(BuiltIn.method)
// // .transform(Common.object, value(0))
// // .literal(Common.name, "toString");
// // end.then(toString);
// // toString.then(rewrite(1, identifier, toString));
// // }
// // }

// // val whitespace = new SynapticNode();
// // indexNode(process, whitespace, "whitespace");
// // {
// // kb.eavNode(true, false, isWhitespace, kb.node(true))
// // .then(whitespace)
// // // For now, just drop all whitespace.
// // .then(rewrite(1));
// // }

// // // The operator rule being distinct lets us do things like strip
// whitespace
// // // without collapsing "= =" into operator ==.
// // val operator = new SynapticNode();
// // indexNode(process, operator, "operator");
// // {
// // operator.conjunction(isCodePoint, isWhitespace, isIdentifierPart)
// // .inhibitor(kb.eavNode(true, false, isWhitespace, kb.node(true)))
// // .inhibitor(kb.eavNode(true, false, isIdentifierPart, kb.node(true)))
// // .then(rewrite(1, operator, value(0)));
// // }

// // // Common lookahead logic for rules that can be inhibited by lookahead.
// // Defaults
// // // to deferring until whitespace is consumed and operators are resolved;
// // // roughly, this is the local completion of tokenization, but without
// string
// // // folding.
// // //
// // // It's probably better to wait until after process to call
// needsLookahead.
// // val needsLookahead = new SynapticNode();
// // indexNode(process, needsLookahead, "needsLookahead");
// // val lookaheadOk = new SynapticNode();
// // indexNode(needsLookahead, lookaheadOk, "lookaheadOk");
// // {
// // val isLookaheadCodePoint = kb.eavNode(true, false, symbol(1),
// // kb.node(Common.codePoint));

// // val isLookaheadWhitespace = kb.new InvocationNode(BuiltIn.method)
// // .literal(Common.javaClass, Character.class)
// // .literal(Common.name, "isWhitespace")
// // .literal(kb.param(0), int.class)
// // .transform(kb.arg(0), value(1));
// // isLookaheadWhitespace.conjunction(needsLookahead, isLookaheadCodePoint);

// // val isNotLookaheadCodePoint = new SynapticNode();
// // needsLookahead.then(isNotLookaheadCodePoint)
// // .inhibitor(isLookaheadCodePoint);

// // lookaheadOk.disjunction(isNotLookaheadCodePoint, isLookaheadWhitespace)
// // .inhibitor(kb.eavNode(true, false, isLookaheadWhitespace, kb.node(true)));
// // }

// // // Unless otherwise inhibited, rewrite identifiers as calls to
// // // Bootstrap.resolve. In some cases, we could instead just set them as
// // transform
// // // members to invocations, but that can have evaluation order side
// effects.
// // //
// // // The inhibition cases are typically when members or lvalues.
// // val resolvedIdentifier = new SynapticNode();
// // indexNode(process, resolvedIdentifier, "resolvedIdentifier");
// // {
// // val isIdentifier = new SynapticNode().conjunction(process,
// kb.eavNode(true,
// // false, symbol(0), identifier));
// // // Go ahead and set up the lookahead for the inhibitors now; the
// inhibitors
// // will
// // // be added later.
// // isIdentifier.then(needsLookahead);
// // resolvedIdentifier.conjunction(isIdentifier, lookaheadOk);

// // val resolveCall = kb.new
// // InvocationNode(BuiltIn.node).literal(Common.entrypoint,
// Bootstrap.resolve);
// // resolvedIdentifier.then(resolveCall);

// // val createLiteral = kb.new InvocationNode(BuiltIn.node);
// // resolvedIdentifier.then(createLiteral);

// // kb.new InvocationNode(BuiltIn.setProperty).transform(Common.object,
// // resolveCall)
// // .literal(Common.name, Common.literal)
// // .transform(Common.value, createLiteral)
// // .conjunction(resolveCall, createLiteral);

// // kb.new InvocationNode(BuiltIn.setProperty).transform(Common.object,
// // createLiteral)
// // .literal(Common.name, Common.name)
// // .transform(Common.value, value(0))
// // .conjunction(resolvedIdentifier, createLiteral);

// // resolveCall.then(rewrite(1, expression, resolveCall));
// // }

// // val memberLiteral = new SynapticNode();
// // indexNode(process, memberLiteral, "memberLiteral");
// // {
// // val dot = new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(-1), operator),
// // kb.eavNode(true, false, value(-1), kb.node((int) '.')));
// // memberLiteral.conjunction(process, dot, kb.eavNode(true, false, symbol(0),
// // identifier))
// // .then(rewrite(2, memberLiteral, value(0)));
// // resolvedIdentifier.inhibitor(dot);
// // }

// // val memberExpression = new SynapticNode();
// // indexNode(process, memberExpression, "memberExpression");
// // mark(memberExpression, expression);
// // val indexer = new SynapticNode();
// // indexNode(process, indexer, "indexer");
// // {
// // indexer.conjunction(process,
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(-2), operator),
// // kb.eavNode(true, false, value(-2), kb.node((int) '['))),
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(0), operator),
// // kb.eavNode(true, false, value(0), kb.node((int) ']'))));

// // new SynapticNode().conjunction(indexer, kb.eavNode(true, false,
// symbol(-1),
// // literal))
// // .then(rewrite(3, memberLiteral, value(-1)));
// // new SynapticNode().conjunction(indexer, kb.eavNode(true, false,
// symbol(-1),
// // expression))
// // .then(rewrite(3, memberExpression, value(-1)));
// // }

// // val getOrCreate = new SynapticNode();
// // indexNode(process, getOrCreate, "getOrCreate");
// // {
// // val get = kb.new
// InvocationNode(BuiltIn.getProperty).inherit(Common.object)
// // .inherit(Common.name);
// // getOrCreate.then(get);
// // val absent = kb.eavNode(true, false, get, null);

// // get.then(kb.new InvocationNode(BuiltIn.setProperty).literal(Common.name,
// // Common.returnValue)
// // .transform(Common.value, get))
// // .inhibitor(absent);

// // val create = kb.new
// InvocationNode(BuiltIn.node).inherit(Common.entrypoint);
// // create.conjunction(get, absent)
// // .then(kb.new InvocationNode(BuiltIn.setProperty).inherit(Common.object)
// // .inherit(Common.name)
// // .transform(Common.value, create))
// // .then(kb.new InvocationNode(BuiltIn.setProperty).literal(Common.name,
// // Common.returnValue)
// // .transform(Common.value, create));
// // }

// // val expressionTail = new SynapticNode();
// // indexNode(expression, expressionTail, "tail");

// // // Given a prior and posterior both with a expressionTail, links the
// // posterior
// // // to the prior expressionTail and replaces the prior expressionTail with
// the
// // // posterior expressionTail.
// // val mergeExpression = new SynapticNode();
// // indexNode(expression, mergeExpression, "merge");
// // {
// // val getPriorTail = kb.new
// // InvocationNode(BuiltIn.getProperty).transform(Common.object, Common.prior)
// // .literal(Common.name, expressionTail);
// // mergeExpression.then(getPriorTail);

// // val getPosteriorTail = kb.new
// // InvocationNode(BuiltIn.getProperty).transform(Common.object,
// // Common.posterior)
// // .literal(Common.name, expressionTail);
// // mergeExpression.then(getPosteriorTail);

// // val link = kb.new
// InvocationNode(BuiltIn.associate).transform(Common.prior,
// // getPriorTail)
// // .inherit(Common.posterior);
// // getPriorTail.then(link);
// // val updatePriorTail = kb.new
// // InvocationNode(BuiltIn.setProperty).transform(Common.object, Common.prior)
// // .literal(Common.name, expressionTail)
// // .transform(Common.value, getPosteriorTail);
// // getPosteriorTail.then(updatePriorTail);
// // }

// // // Given an object, possibly with an entrypoint, and a value expression,
// if
// // the
// // // entrypoint exists, merge it with the expression; otherwise, set the
// // // expression as the entrypoint.
// // val mergePrerequisite = new SynapticNode();
// // indexNode(expression, mergePrerequisite, "merge");
// // {
// // val hasEntrypoint = kb.eavNode(true, true, kb.node(Common.object),
// // kb.node(Common.entrypoint));

// // mergePrerequisite.then(kb.new
// // InvocationNode(BuiltIn.setProperty).inherit(Common.object)
// // .literal(Common.name, Common.entrypoint)
// // .inherit(Common.value))
// // .inhibitor(hasEntrypoint);

// // val getEntrypoint = kb.new
// // InvocationNode(BuiltIn.getProperty).inherit(Common.object)
// // .literal(Common.name, Common.entrypoint)
// // .conjunction(mergePrerequisite, hasEntrypoint);
// // getEntrypoint.then(kb.new
// // InvocationNode(mergeExpression).transform(Common.prior, getEntrypoint)
// // .transform(Common.posterior, Common.value));
// // }

// // // Given a object (typically an invocation), a name, and a symbol/value
// pair,
// // // add the value as a literal or transform property to the object under
// the
// // // given name. If the symbol is an expression, also merge it as a
// // prerequisite.
// // val mergeArgument = new SynapticNode();
// // indexNode(expression, mergeArgument, "mergeArgument");
// // {
// // val isExpression = kb.eavNode(true, true, kb.node(Common.symbol),
// // expression);

// // val mergeLiteralArgument = new SynapticNode().conjunction(mergeArgument)
// // .inhibitor(isExpression);
// // {
// // val goc = kb.new InvocationNode(getOrCreate)
// // .inherit(Common.object)
// // .literal(Common.name, Common.literal);
// // mergeLiteralArgument.then(goc)
// // .then(kb.new InvocationNode(BuiltIn.setProperty)
// // .transform(Common.object, goc)
// // .inherit(Common.name)
// // .inherit(Common.value));
// // }

// // val mergeExpressionArgument = new
// SynapticNode().conjunction(mergeArgument,
// // isExpression);
// // {
// // val goc = kb.new InvocationNode(getOrCreate)
// // .inherit(Common.object)
// // .literal(Common.name, Common.transform);
// // mergeExpressionArgument.then(goc);

// // val getTail = kb.new InvocationNode(BuiltIn.getProperty)
// // .transform(Common.object, Common.value)
// // .literal(Common.name, expressionTail);
// // mergeExpressionArgument.then(getTail);

// // kb.new InvocationNode(BuiltIn.setProperty)
// // .transform(Common.object, goc)
// // .inherit(Common.name)
// // .transform(Common.value, getTail)
// // .conjunction(goc, getTail);

// // mergeExpressionArgument.then(kb.new InvocationNode(mergePrerequisite)
// // .inherit(Common.object)
// // .inherit(Common.value));
// // }
// // }

// // // Given an object expression, possibly with an entrypoint, if the
// entrypoint
// // // exists, mergeExpression it with the object and return the entrypoint.
// // // Otherwise, return the object itself.
// // val finalizePrerequisites = new SynapticNode();
// // indexNode(expression, finalizePrerequisites, "finalize");
// // {
// // val hasEntrypoint = kb.eavNode(true, true, kb.node(Common.object),
// // kb.node(Common.entrypoint));

// // finalizePrerequisites.then(kb.new
// // InvocationNode(BuiltIn.setProperty).literal(Common.name,
// Common.returnValue)
// // .transform(Common.value, Common.object))
// // .inhibitor(hasEntrypoint);

// // val getEntrypoint = kb.new
// // InvocationNode(BuiltIn.getProperty).inherit(Common.object)
// // .literal(Common.name, Common.entrypoint)
// // .conjunction(finalizePrerequisites, hasEntrypoint);
// // getEntrypoint.then(kb.new
// // InvocationNode(mergeExpression).transform(Common.prior, getEntrypoint)
// // .transform(Common.posterior, Common.object))
// // .then(kb.new InvocationNode(BuiltIn.setProperty).literal(Common.name,
// // Common.returnValue)
// // .transform(Common.value, getEntrypoint));
// // }

// // // Both rvalue and lvalue members will be rewritten as "member folds",
// with a
// // // value that is folded into. These rules handle that.
// // //
// // // To use, inject a memberFold symbol after the member, with a symbol
// // property
// // // on the value for the symbol that will ultimately be written.
// // //
// // // Does not finalize the fold.
// // val memberFold = new SynapticNode();
// // indexNode(process, memberFold, "memberFold");
// // {
// // val wantLhs = memberFold, wantRhs = new SynapticNode();

// // {
// // val lhsEav = kb.eavNode(true, false, symbol(0), wantLhs),
// // valueEav = kb.eavNode(true, true, symbol(-2), parseValue);

// // // no object
// // rewrite(1, wantRhs, value(0))
// // .conjunction(process, lhsEav)
// // .inhibitor(valueEav);

// // val hasObject = new SynapticNode().conjunction(process, lhsEav, valueEav);

// // hasObject.then(kb.new InvocationNode(mergeArgument)
// // .transform(Common.object, value(0))
// // .literal(Common.name, Common.object)
// // .transform(Common.symbol, symbol(-2))
// // .transform(Common.value, value(-2)));
// // hasObject.then(rewrite(3)
// // .transform(symbol(1), symbol(-1))
// // .transform(value(1), value(-1))
// // .transform(symbol(2), wantRhs)
// // .transform(value(2), value(0)));
// // }

// // {
// // val foldRhs = new SynapticNode().conjunction(process, kb.eavNode(true,
// false,
// // symbol(0), wantRhs));

// // foldRhs.then(kb.new InvocationNode(mergeArgument)
// // .transform(Common.object, value(0))
// // .literal(Common.name, Common.name)
// // .transform(Common.symbol, symbol(-1))
// // .transform(Common.value, value(-1)));

// // val getSymbol = kb.new
// // InvocationNode(BuiltIn.getProperty).transform(Common.object, value(0))
// // .literal(Common.name, Common.symbol);
// // foldRhs.then(getSymbol)
// // .then(rewrite(2)
// // .transform(Common.symbol, getSymbol)
// // .transform(Common.value, value(0)));
// // }
// // }

// // val rvalueMember = new SynapticNode();

// // indexNode(process, rvalueMember, "rvalueMember");
// // {
// // val isMember = new SynapticNode().conjunction(process,
// // new SynapticNode().disjunction(
// // kb.eavNode(true, false, symbol(0), memberLiteral),
// // kb.eavNode(true, false, symbol(0), memberExpression)));

// // // Use lookahead to inhibit if this will be an lvalue.
// // isMember.then(needsLookahead);

// // rvalueMember.conjunction(isMember, lookaheadOk)
// // .inhibitor(new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(1), operator),
// // kb.eavNode(true, false, value(1), kb.node((int) '='))));

// // val getProperty = kb.new
// // InvocationNode(BuiltIn.node).literal(Common.entrypoint,
// BuiltIn.getProperty);
// // indexNode(rvalueMember, getProperty, "getProperty");
// // rvalueMember.then(getProperty);

// // getProperty.then(kb.new
// // InvocationNode(BuiltIn.setProperty).transform(Common.object, getProperty)
// // .literal(Common.name, expressionTail)
// // .transform(Common.value, getProperty));
// // getProperty.then(kb.new
// // InvocationNode(BuiltIn.setProperty).transform(Common.object, getProperty)
// // .literal(Common.name, Common.symbol)
// // .literal(Common.value, getProperty));
// // getProperty.then(rewrite(0, memberFold, getProperty));

// // val finalize = kb.new
// // InvocationNode(finalizePrerequisites).transform(Common.object, value(0))
// // .conjunction(process, kb.eavNode(true, false, symbol(0), getProperty));
// // finalize.then(rewrite(1, expression, finalize));
// // }

// // // Baking uses `backticks` to evaluate an expression at parse time and
// bake
// // the
// // // result in as a literal. Note that surrounding a literal in backticks
// would
// // // conceivably be passthrough and is not supported by default.
// // val bake = new SynapticNode();
// // indexNode(process, bake, "bake");
// // {
// // bake.conjunction(process, new SynapticNode().conjunction(
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(-2), operator),
// // kb.eavNode(true, false, value(-2), kb.node((int) '`'))),
// // kb.eavNode(true, false, symbol(-1), expression),
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(0), operator),
// // kb.eavNode(true, false, value(0), kb.node((int) '`')))));

// // val getTail = kb.new
// // InvocationNode(BuiltIn.getProperty).transform(Common.object, value(-1))
// // .literal(Common.name, expressionTail);
// // bake.then(getTail);
// // val getResult = kb.new
// // InvocationNode(BuiltIn.getProperty).transform(Common.name, getTail);
// // val linkResult = kb.new
// // InvocationNode(BuiltIn.associate).transform(Common.prior, getTail)
// // .literal(Common.posterior, getResult);
// // getTail.then(linkResult);

// // val activate = kb.new
// // InvocationNode(BuiltIn.activate).transform(Common.value, value(-1));
// // linkResult.then(activate);
// // getResult.then(rewrite(3, literal, getResult));
// // }

// // val parenExpr = new SynapticNode();
// // indexNode(process, parenExpr, "parenExpr");
// // {
// // // This does not conflict with assignment since that will eagerly consume
// the
// // // opening parenthesis.
// // parenExpr.conjunction(
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(-2), operator),
// // kb.eavNode(true, false, value(-2), kb.node((int) '('))),
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(0), operator),
// // kb.eavNode(true, false, value(0), kb.node((int) ')'))));
// // parenExpr.then(rewrite(3).transform(Common.symbol, symbol(-1))
// // .transform(Common.value, value(-1)));
// // }

// // val braceExpr = new SynapticNode();
// // indexNode(process, braceExpr, "braceExpr");
// // {
// // braceExpr.conjunction(
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(-2), operator),
// // kb.eavNode(true, false, value(-2), kb.node((int) '{'))),
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(0), operator),
// // kb.eavNode(true, false, value(0), kb.node((int) '}'))));
// // braceExpr.then(rewrite(3).transform(Common.symbol, symbol(-1))
// // .transform(Common.value, value(-1)));
// // }

// // val assignment = new SynapticNode();
// // indexNode(process, assignment, "assignment");
// // {
// // val lhs = new SynapticNode();
// // indexNode(assignment, lhs, "lhs");
// // lhs.conjunction(process, new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(0), operator),
// // kb.eavNode(true, false, value(0), kb.node((int) '='))));

// // val setProperty = kb.new
// // InvocationNode(BuiltIn.node).literal(Common.entrypoint,
// BuiltIn.setProperty);
// // indexNode(assignment, setProperty, "setProperty");

// // {
// // lhs.then(setProperty);

// // setProperty.then(kb.new InvocationNode(BuiltIn.setProperty)
// // .transform(Common.object, setProperty)
// // .literal(Common.name, expressionTail)
// // .transform(Common.value, setProperty));
// // setProperty.then(kb.new InvocationNode(BuiltIn.setProperty)
// // .transform(Common.object, setProperty)
// // .literal(Common.name, Common.symbol)
// // .literal(Common.value, setProperty));

// // resolvedIdentifier.inhibitor(new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(1), operator),
// // kb.eavNode(true, false, value(1), kb.node((int) '='))));

// // // If the lhs is a simple literal/identifier or expression, rewrite as a
// // // memberLiteral or
// // // memberExpression.
// // rewrite(2)
// // .literal(symbol(1), memberLiteral)
// // .transform(value(1), value(-1))
// // .literal(symbol(2), memberFold)
// // .transform(value(2), setProperty)
// // .conjunction(setProperty, new SynapticNode().disjunction(
// // kb.eavNode(true, false, symbol(-1), literal),
// // kb.eavNode(true, false, symbol(-1), identifier)));
// // rewrite(2)
// // .literal(symbol(1), memberExpression)
// // .transform(value(1), value(-1))
// // .literal(symbol(2), memberFold)
// // .transform(value(2), setProperty)
// // .conjunction(setProperty, kb.eavNode(true, false, symbol(-1),
// expression));

// // // Otherwise just inject the memberFold in place of operator =.
// // setProperty.then(rewrite(1, memberFold, setProperty))
// // .inhibitor(kb.eavNode(true, true, symbol(-1), parseValue));
// // }

// // val rhs = new SynapticNode();
// // indexNode(assignment, rhs, "rhs");

// // {
// // val rhsLookaheadOk = new SynapticNode();
// // indexNode(assignment, rhsLookaheadOk, "lookaheadOk");
// // rhsLookaheadOk.disjunction(
// // process.then(new SynapticNode())
// // .inhibitor(kb.eavNode(true, true, symbol(1))),
// // new SynapticNode().conjunction(
// // new SynapticNode().disjunction(
// // kb.eavNode(true, false, symbol(1), kb.node(Common.codePoint)),
// // kb.eavNode(true, false, symbol(1), operator)),
// // new SynapticNode().disjunction(
// // kb.eavNode(true, false, value(1), kb.node((int) ')')),
// // kb.eavNode(true, false, value(1), kb.node((int) '}')),
// // kb.eavNode(true, false, value(1), kb.node((int) ';')))));

// // rhs.conjunction(rhsLookaheadOk, kb.eavNode(true, true, symbol(0),
// // parseValue));

// // assignment.conjunction(rhs, kb.eavNode(true, false, symbol(-1),
// // setProperty));

// // val finalize = kb.new InvocationNode(finalizePrerequisites)
// // .transform(Common.object, value(-1));
// // assignment
// // .then(kb.new InvocationNode(mergeArgument)
// // .transform(Common.object, value(-1))
// // .literal(Common.name, Common.value)
// // .transform(Common.symbol, symbol(0))
// // .transform(Common.value, value(0)))
// // .then(finalize)
// // .then(rewrite(2, expression, finalize));
// // }
// // }

// // final Node index = parse("index");
// // index.properties.put(kb.node("true"), kb.node(true));
// // index.properties.put(kb.node("false"), kb.node(false));

// // val call = new SynapticNode();
// // indexNode(process, call, "call");
// // {
// // val startCall = new SynapticNode();
// // indexNode(call, startCall, "start");
// // startCall.conjunction(process,
// // kb.eavNode(true, true, symbol(-1), parseValue),
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(0), operator),
// // kb.eavNode(true, false, value(0), kb.node((int) '('))));

// // val indirectCallEntrypoint = new SynapticNode();
// // val indirectCall = kb.new InvocationNode(BuiltIn.activate)
// // .transform(Common.value, indirectCallEntrypoint);
// // indexNode(indirectCall, indirectCallEntrypoint, "entrypoint");

// // val invocation = new SynapticNode();
// // indexNode(call, invocation, "invocation");

// // {
// // val create = kb.new
// InvocationNode(BuiltIn.node).literal(Common.entrypoint,
// // indirectCall);
// // indexNode(invocation, create, "create");
// // startCall.then(create);
// // create
// // .then((SynapticNode) parse(
// // "[`parse.call.invocation.create`][`parse.expression.tail`] =
// // [`parse.call.invocation.create`]"))
// // .then(kb.new InvocationNode(mergeArgument)
// // .transform(Common.object, create)
// // .literal(Common.name, indirectCallEntrypoint)
// // .transform(Common.symbol, symbol(-1))
// // .transform(Common.value, value(-1)));
// // create.then(rewrite(2, invocation, create));
// // }

// // val cParen = new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(0), operator),
// // kb.eavNode(true, false, value(0), kb.node((int) ')')));
// // val comma = new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(0), operator),
// // kb.eavNode(true, false, value(0), kb.node((int) ',')));

// // val finalize = kb.new
// // InvocationNode(finalizePrerequisites).transform(Common.object, value(-1));
// // finalize
// // .conjunction(
// // process,
// // kb.eavNode(true, false, symbol(-1), invocation),
// // cParen)
// // .then(rewrite(2, call, finalize));

// // val argDelimiter = new SynapticNode().disjunction(comma, cParen);

// // val namedArg = new SynapticNode();
// // indexNode(call, namedArg, "namedArg");
// // namedArg
// // .conjunction(
// // process,
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(-4), invocation),
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(-2), operator),
// // kb.eavNode(true, false, value(-2), kb.node((int) ':')),
// // kb.eavNode(true, true, symbol(-1), parseValue)),
// // argDelimiter))
// // .then(kb.new InvocationNode(mergeArgument)
// // .transform(Common.object, value(-4))
// // .transform(Common.name, value(-3))
// // .transform(Common.symbol, symbol(-1))
// // .transform(Common.value, value(-1)));

// // // For now, auto-bake argument names. In the future, we may instead want
// to
// // // suppress identifier resolution or take a more contextual approach, but
// // baking
// // // is the common case right now.
// // val autoBake = new SynapticNode();
// // indexNode(namedArg, autoBake, "autoBake");
// // autoBake.conjunction(
// // process,
// // kb.eavNode(true, false, symbol(0), expression),
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(1), operator),
// // kb.eavNode(true, false, value(1), kb.node((int) ':'))));
// // autoBake.then(rewrite(1)
// // .literal(symbol(1), operator)
// // .literal(value(1), kb.node((int) '`'))
// // .transform(symbol(2), symbol(0))
// // .transform(value(2), value(0))
// // .literal(symbol(3), operator)
// // .literal(value(3), kb.node((int) '`')));

// // rewrite(5, invocation, value(-4)).conjunction(namedArg, comma);
// // rewrite(5)
// // .literal(symbol(1), invocation)
// // .transform(value(1), value(-4))
// // .transform(symbol(2), symbol(0))
// // .transform(value(2), value(0))
// // .conjunction(namedArg, cParen);
// // }

// // // Statements are a broadening of expressions.
// // val statement = new SynapticNode();
// // indexNode(process, statement, "statement");
// // {
// // // For now, only expressions can become statements, but we'll add things
// like
// // // declarations and blocks later.
// // val symbolOk = new SynapticNode().disjunction(kb.eavNode(true, false,
// // symbol(0), expression));
// // indexNode(statement, symbolOk, "symbolOk");

// // val lookbehind = // No previous symbol, or statement.
// // new SynapticNode().disjunction(
// // new SynapticNode().conjunction(process)
// // .inhibitor(kb.eavNode(true, true, symbol(-1))),
// // kb.eavNode(true, true, symbol(-1), statement));

// // statement.conjunction(
// // process,
// // new SynapticNode().conjunction(
// // lookbehind,
// // symbolOk,
// // // No next symbol, or ';'.
// // new SynapticNode().disjunction(
// // new SynapticNode().conjunction(process)
// // .inhibitor(kb.eavNode(true, true, symbol(1))),
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(1), operator),
// // kb.eavNode(true, false, value(1), kb.node((int) ';'))))));

// // statement.then(rewrite(1, statement, value(0)));

// // // Delete delimiting semicolons.
// // rewrite(1).conjunction(
// // lookbehind,
// // kb.eavNode(true, false, symbol(0), operator),
// // kb.eavNode(true, false, value(0), kb.node((int) ';')));
// // }

// // val statementSequence = new SynapticNode();
// // indexNode(process, statementSequence, "statementSequence");
// // {
// // statementSequence.conjunction(
// // process,
// // kb.eavNode(true, false, symbol(-1), statement),
// // kb.eavNode(true, false, symbol(0), statement));
// // statementSequence.then(kb.new InvocationNode(mergeExpression)
// // .transform(Common.prior, value(-1))
// // .transform(Common.posterior, value(0)));
// // statementSequence.then(rewrite(2, statement, value(-1)));
// // }

// // val stringLiteral = new SynapticNode();
// // indexNode(process, stringLiteral, "stringLiteral");
// // {
// // val buffer = new SynapticNode();
// // indexNode(stringLiteral, buffer, "buffer");

// // val start = new SynapticNode();
// // indexNode(stringLiteral, start, "start");
// // {
// // start
// // .conjunction(
// // process,
// // kb.eavNode(true, false, symbol(0), operator),
// // kb.eavNode(true, false, value(0), kb.node((int) '"')))
// // .inhibitor(kb.eavNode(true, false, symbol(-1), buffer));

// // val newBuffer = kb.new
// // InvocationNode(Bootstrap.newInstance).literal(Common.javaClass,
// // StringBuilder.class);
// // indexNode(start, newBuffer, "newBuffer");
// // start.then(newBuffer)
// // .then(rewrite(1, buffer, newBuffer));
// // }

// // // Suspend other parse rules while a string literal is being parsed.
// // val followsBuffer = kb.eavNode(true, false, symbol(-1), buffer);
// // operator.inhibitor(followsBuffer);
// // whitespace.inhibitor(followsBuffer);
// // ((SynapticNode) eval("parse.identifier.start")).inhibitor(followsBuffer);

// // val part = new SynapticNode();
// // indexNode(stringLiteral, part, "part");
// // {
// // part.conjunction(isCodePoint, kb.eavNode(true, false, symbol(-1), buffer))
// // .inhibitor(kb.eavNode(true, false, value(0), kb.node((int) '"')));

// // part.then(kb.new InvocationNode(BuiltIn.method)
// // .transform(Common.object, value(-1))
// // .literal(Common.name, "appendCodePoint")
// // .literal(kb.param(0), int.class)
// // .transform(kb.arg(0), value(0)));

// // // Simply delete the trailing code point now that we've folded it into the
// // // buffer.
// // part.then(rewrite(1));
// // }

// // val end = new SynapticNode();
// // indexNode(stringLiteral, end, "end");
// // {
// // end.conjunction(
// // isCodePoint,
// // kb.eavNode(true, false, symbol(-1), buffer),
// // kb.eavNode(true, false, value(0), kb.node((int) '"')));

// // val toString = kb.new InvocationNode(BuiltIn.method)
// // .transform(Common.object, value(-1))
// // .literal(Common.name, "toString");
// // end.then(toString)
// // .then(rewrite(2, literal, toString));
// // }
// // }

// // // As the language gets more capable, we can start parsing longer and
// longer
// // // strings so we'll want to reset the recursion limit while progress is
// being
// // // made.
// // {
// // parse.then((SynapticNode) parse("maxStackDepthOnReset =
// [`maxStackDepth`]"));
// // val initLength = (SynapticNode) parse("""
// // [`parse.rewriter`].shortestLength = `method`(
// // object: [`parse.rewriter`],
// // name: "length")
// // """);
// // val rewriter = eval("parse.rewriter");
// // rewriter.then(initLength);
// // val advance = (SynapticNode) eval("parse.advance");
// // initLength.then(advance);
// // advance.getSynapse()
// // .dissociate(rewriter);

// // val invokeApply = eval("parse.invokeApply");
// // val recurse = (InvocationNode) eval("parse.recurse");
// // recurse.inherit("maxStackDepthOnReset");
// // // Temporarily remove the stack depth limit on parse
// // recurse.literal(Common.maxStackDepth,
// KnowledgeBase.DEFAULT_MAX_STACK_DEPTH);

// // val compareLengths = (SynapticNode) parse("""
// // `method`(
// // `object`: `method`(
// // `object`: [`parse.rewriter`],
// // `name`: "length"),
// // `name`: "compareTo",
// // `_param1`: `findClass(`name`: "java.lang.Integer")`,
// // `_arg1`: [`parse.rewriter`].shortestLength)
// // """,
// // ImmutableMap.of(
// // "_param1", kb.param(0),
// // "_arg1", kb.arg(0)));
// // recurse.literal(Common.maxStackDepth, null);

// // invokeApply.then(compareLengths);
// // val shorter = kb.eavNode(true, false, tail(compareLengths), kb.node(-1));

// // // If the length got shorter, first reset the max stack depth.
// // shorter.then((SynapticNode) parse("`maxStackDepth` =
// maxStackDepthOnReset"))
// // .then(recurse);
// // tail(compareLengths).then(recurse)
// // .inhibitor(shorter);
// // recurse.getSynapse()
// // .dissociate(invokeApply);
// // }

// // // If an expression (or other statement-like subroutine) is preceded by an
// // // 'apostrophe, treat it as a literal (like a lambda).
// // val expressionLiteral = new SynapticNode();
// // indexNode(process, expressionLiteral, "expressionLiteral");
// // {
// // expressionLiteral.conjunction(
// // process,
// // new SynapticNode().conjunction(
// // kb.eavNode(true, false, symbol(-1), operator),
// // kb.eavNode(true, false, value(-1), kb.node((int) '\''))),
// // kb.eavNode(true, true, symbol(0), statement))
// // .then(rewrite(2, literal, value(0)));
// // }

// // val intLiteral = new SynapticNode();
// // indexNode(process, intLiteral, "intLiteral");
// // {
// // val builder = new SynapticNode();
// // indexNode(intLiteral, builder, "builder");

// // eval("""
// // parse.isDigit = '`method`(
// // `javaClass`: `findClass(`name`: "java.lang.Character")`,
// // `name`: "isDigit",
// // `_param1`: '`findClass(`name`: "int")`,
// // `_arg1`: [`_v0`]);
// // associate(`prior`: parse.isCodePoint, `posterior`: parse.isDigit);
// // """);

// // val isDigit = new SynapticNode();
// // isDigit.conjunction(isCodePoint, eval("eavNode(name: 'parse.isDigit,
// value:
// // 'true)"));

// // val followsBuilder = kb.eavNode(true, false, symbol(-1), builder);

// // val start = new SynapticNode();
// // indexNode(intLiteral, start, "start");
// // {
// // isDigit.then(start);
// // start.getSynapse()
// // .setCoefficient(followsBuilder, -1);
// // start.getSynapse()
// // .setCoefficient(kb.eavNode(true, false, symbol(-1),
// // eval("'parse.identifier.buffer")), -1);
// // start.getSynapse()
// // .setCoefficient(kb.eavNode(true, false, symbol(-1),
// // eval("'parse.stringLiteral.buffer")), -1);

// // val newBuilder = (SynapticNode) parse("newInstance(javaClass:
// // '`findClass(name: \"ai.xng.IntBuilder\")`)");
// // indexNode(start, newBuilder, "newBuilder");
// // start.then(newBuilder);
// // val append = (SynapticNode) parse("""
// // method(
// // object: `'parse.intLiteral.start.newBuilder`,
// // name: "append",
// // _param1: '`findClass(name: "int")`,
// // _arg1: _v0
// // )
// // """);
// // newBuilder.then(append);
// // newBuilder.then(rewrite(1, builder, newBuilder));
// // }

// // val part = new SynapticNode();
// // indexNode(intLiteral, part, "part");
// // {
// // part.conjunction(isDigit, followsBuilder);

// // val append = (SynapticNode) parse("""
// // method(
// // object: _vn1,
// // name: "append",
// // _param1: '`findClass(name: "int")`,
// // _arg1: _v0
// // )
// // """);
// // part.then(append);
// // // Simply delete the trailing code point now that we've folded it into the
// // // builder.
// // part.then(rewrite(1));
// // }

// // val end = new SynapticNode();
// // indexNode(intLiteral, end, "end");
// // {
// // end.conjunction(process, kb.eavNode(true, false, symbol(0), builder));
// // // We can get away with this simple inhibition because any non-digit
// // character
// // // coming up will be consumed as an identifier start, whitespace, or an
// // // operator, after which we'll backtrack to here.
// // end.getSynapse()
// // .setCoefficient(kb.eavNode(true, false, symbol(1),
// // kb.node(Common.codePoint)), -1);

// // val minus = kb.eavNode(true, false, value(-1), kb.node((int) '-'));

// // val positive = new SynapticNode();
// // end.then(positive);
// // positive.getSynapse()
// // .setCoefficient(minus, -1);

// // val get = (SynapticNode) parse("method(object: _v0, name: \"get\")");
// // positive.then(get);
// // get.then(rewrite(1, nodeLiteral, get));

// // val negative = new SynapticNode();
// // negative.conjunction(end, minus);
// // // Let's take the positive path out for a spin.
// // negative.then((SynapticNode) parse("""
// // rewrite(
// // rewriter: rewriter,
// // rewriteLength: 2,
// // symbol: '`'parse.nodeLiteral`,
// // value: method(
// // object: ``'BuiltIn.ordinal`(
// // type: 'value,
// // `'Common.ordinal`: 0)`,
// // name: "getNegative")
// // )
// // """).properties.get(kb.node(Common.entrypoint)));
// // }
// // }
// }
// }
