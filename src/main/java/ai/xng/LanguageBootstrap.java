package ai.xng;

import ai.xng.KnowledgeBase.Bootstrap;
import ai.xng.KnowledgeBase.BuiltIn;
import ai.xng.KnowledgeBase.Common;
import ai.xng.KnowledgeBase.InvocationNode;
import ai.xng.KnowledgeBase.Ordinal;
import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor
public class LanguageBootstrap {
  private final KnowledgeBase kb;

  public void indexNode(final Node index, final Node node, final String name) {
    node.comment = name;
    index.properties.put(kb.node(name), node);
  }

  public Node parse(final String string) {
    try {
      val context = kb.newContext();
      context.fatalOnExceptions();
      // Although naively we could activate parse directly, the easiest way to extract
      // a return value from a tail recursed subroutine is through an invocation node.
      val parse = kb.new InvocationNode(kb.node(Bootstrap.parse)).literal(kb.node(Common.value), kb.node(string));
      parse.activate(context);
      context.blockUntilIdle();

      val rewriter = (DeterministicNGramRewriter) context.node.properties.get(parse).getValue();
      val result = rewriter.single();

      if (result == null) {
        throw new NullPointerException(String.format("Parse result was null. %s", rewriter.debug()));
      }

      return result;
    } catch (final RuntimeException e) {
      throw new IllegalArgumentException(String.format("Failed to parse \"%s\"", string), e);
    }
  }

  private Node symbol(final int ordinal) {
    return kb.node(new Ordinal(kb.node(Common.symbol), ordinal));
  }

  private Node value(final int ordinal) {
    return kb.node(new Ordinal(kb.node(Common.value), ordinal));
  }

  private InvocationNode rewrite(final int length) {
    return kb.new InvocationNode(kb.node(BuiltIn.rewrite)).inherit(kb.node(Common.rewriter))
        .literal(kb.node(Common.rewriteLength), kb.node(length));
  }

  private InvocationNode rewrite(final int length, final Node symbol, final Node value) {
    return rewrite(length).literal(kb.node(Common.symbol), symbol).transform(kb.node(Common.value), value);
  }

  /**
   * Expand eval/parse into a self-sustaining language.
   */
  public void bootstrap() {
    val parse = kb.node(Bootstrap.parse);
    val apply = parse.properties.get(kb.node("apply"));

    val isCodePoint = new SynapticNode();
    indexNode(parse, isCodePoint, "isCodePoint");
    isCodePoint.conjunction(apply, kb.eavNode(true, false, symbol(0), kb.node(Common.codePoint)));

    val isIdentifierStart = kb.new InvocationNode(kb.node(BuiltIn.method))
        .literal(kb.node(Common.javaClass), kb.node(Character.class))
        .literal(kb.node(Common.name), kb.node("isJavaIdentifierStart")).literal(kb.param(1), kb.node(int.class))
        .transform(kb.arg(1), value(0));
    indexNode(parse, isIdentifierStart, "isIdentifierStart");
    isCodePoint.then(isIdentifierStart);

    val isIdentifierPart = kb.new InvocationNode(kb.node(BuiltIn.method))
        .literal(kb.node(Common.javaClass), kb.node(Character.class))
        .literal(kb.node(Common.name), kb.node("isJavaIdentifierPart")).literal(kb.param(1), kb.node(int.class))
        .transform(kb.arg(1), value(0));
    indexNode(parse, isIdentifierPart, "isIdentifierPart");
    isCodePoint.then(isIdentifierPart);

    val isWhitespace = kb.new InvocationNode(kb.node(BuiltIn.method))
        .literal(kb.node(Common.javaClass), kb.node(Character.class))
        .literal(kb.node(Common.name), kb.node("isWhitespace")).literal(kb.param(1), kb.node(int.class))
        .transform(kb.arg(1), value(0));
    indexNode(parse, isWhitespace, "isWhitespace");
    isCodePoint.then(isWhitespace);

    val identifier = new SynapticNode();
    indexNode(parse, identifier, "identifier");
    {
      val buffer = new SynapticNode();
      indexNode(identifier, buffer, "buffer");

      val hasBuffer = kb.eavNode(true, false, symbol(-1), buffer);

      val start = new SynapticNode();
      indexNode(identifier, start, "start");
      {
        isIdentifierStart.getSynapse().setCoefficient(hasBuffer, -1);

        kb.eavNode(true, false, isIdentifierStart, kb.node(true)).then(start);

        val newBuffer = kb.new InvocationNode(kb.node(Bootstrap.newInstance)).literal(kb.node(Common.javaClass),
            kb.node(StringBuilder.class));
        newBuffer.comment = "newBuffer";
        start.then(newBuffer);

        val append = kb.new InvocationNode(kb.node(BuiltIn.method)).transform(kb.node(Common.object), newBuffer)
            .literal(kb.node(Common.name), kb.node("appendCodePoint")).literal(kb.param(1), kb.node(int.class))
            .transform(kb.arg(1), value(0));
        newBuffer.then(append);
        append.then(rewrite(1, buffer, newBuffer));
      }

      val part = new SynapticNode();
      indexNode(identifier, part, "part");
      {
        part.conjunction(hasBuffer, kb.eavNode(true, false, isIdentifierPart, kb.node(true)));

        val append = kb.new InvocationNode(kb.node(BuiltIn.method)).transform(kb.node(Common.object), value(-1))
            .literal(kb.node(Common.name), kb.node("appendCodePoint")).literal(kb.param(1), kb.node(int.class))
            .transform(kb.arg(1), value(0));
        part.then(append);

        // Simply delete the trailing code point now that we've folded it into the
        // buffer.
        part.then(rewrite(1));
      }

      val end = new SynapticNode();
      indexNode(identifier, end, "end");
      {
        end.conjunction(apply, kb.eavNode(true, false, symbol(0), buffer));
        // We can get away with this simple inhibition because any non-identifier
        // character coming up will be consumed as whitespace or an operator, after
        // which we'll backtrack to here.
        end.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(1), kb.node(Common.codePoint)), -1);

        val toString = kb.new InvocationNode(kb.node(BuiltIn.method)).transform(kb.node(Common.object), value(0))
            .literal(kb.node(Common.name), kb.node("toString"));
        end.then(toString);
        toString.then(rewrite(1, identifier, toString));
      }
    }

    val resolvedIdentifier = new SynapticNode();
    indexNode(parse, resolvedIdentifier, "resolvedIdentifier");
    {
      resolvedIdentifier.conjunction(apply, kb.eavNode(true, false, symbol(0), identifier));
      val resolve = kb.new InvocationNode(kb.node(Bootstrap.resolve)).transform(kb.node(Common.name), value(0));
      resolvedIdentifier.then(resolve);
      resolve.then(rewrite(1, resolvedIdentifier, resolve));
    }

    val whitespace = new SynapticNode();
    indexNode(parse, whitespace, "whitespace");
    {
      kb.eavNode(true, false, isWhitespace, kb.node(true)).then(whitespace);

      // For now, just drop all whitespace.
      whitespace.then(rewrite(1));
    }

    // The operator rule being distinct lets us do things like strip whitespace
    // without collapsing "= =" into operator ==.
    val operator = new SynapticNode();
    indexNode(parse, operator, "operator");
    {
      operator.conjunction(isCodePoint, isWhitespace, isIdentifierPart);
      operator.getSynapse().setCoefficient(kb.eavNode(true, false, isWhitespace, kb.node(true)), -1);
      operator.getSynapse().setCoefficient(kb.eavNode(true, false, isIdentifierPart, kb.node(true)), -1);

      operator.then(rewrite(1, operator, value(0)));
    }

    val nodeLiteral = new SynapticNode();
    indexNode(parse, nodeLiteral, "nodeLiteral");
    nodeLiteral.conjunction(apply, kb.eavNode(true, false, value(-1), kb.node((int) '\'')),
        kb.eavNode(true, false, symbol(0), resolvedIdentifier));
    nodeLiteral.then(rewrite(2, nodeLiteral, value(0)));

    val memberSelect = new SynapticNode();
    indexNode(parse, memberSelect, "memberSelect");
    memberSelect.conjunction(apply, kb.eavNode(true, false, value(-1), kb.node((int) '.')),
        kb.eavNode(true, false, symbol(0), resolvedIdentifier));
    memberSelect.then(rewrite(2, memberSelect, value(0)));

    val indexer = new SynapticNode();
    indexNode(parse, indexer, "indexer");
    {
      indexer.conjunction(apply, kb.eavNode(true, false, value(-2), kb.node((int) '[')),
          kb.eavNode(true, false, symbol(0), operator), kb.eavNode(true, false, value(0), kb.node((int) ']')));

      // If the term in the indexer is a literal, rewrite it as a member select.
      val literalTerm = kb.eavNode(true, false, symbol(-1), nodeLiteral);

      val transformIndexer = new SynapticNode();
      indexer.then(transformIndexer);
      transformIndexer.getSynapse().setCoefficient(literalTerm, -1);
      transformIndexer.then(rewrite(3, indexer, value(-1)));

      val literalIndexer = new SynapticNode();
      literalIndexer.conjunction(indexer, literalTerm);
      literalIndexer.then(rewrite(3, memberSelect, value(-1)));
    }

    // Given an object with an existing entrypoint and a symbol/value pair, if the
    // symbol is not a literal or identifier and the value has an entrypoint
    // property, links the value to the existing entrypoint and replaces the
    // entrypoint with the new entrypoint.
    val mergeEntrypoint = new SynapticNode();
    indexNode(parse, mergeEntrypoint, "mergeEntrypoint");
    {
      val getObjectEntrypoint = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
          .transform(kb.node(Common.object), kb.node(Common.object))
          .literal(kb.node(Common.name), kb.node(Common.entrypoint));
      mergeEntrypoint.then(getObjectEntrypoint);

      val getValueEntrypoint = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
          .transform(kb.node(Common.object), kb.node(Common.value))
          .literal(kb.node(Common.name), kb.node(Common.entrypoint));
      mergeEntrypoint.then(getValueEntrypoint);

      val associateTail = kb.new InvocationNode(kb.node(BuiltIn.associate))
          .transform(kb.node(Common.prior), kb.node(Common.value))
          .transform(kb.node(Common.posterior), getObjectEntrypoint);
      associateTail.conjunction(getObjectEntrypoint, getValueEntrypoint);
      associateTail.getSynapse().setCoefficient(kb.eavNode(true, false, getValueEntrypoint, null), -1);
      associateTail.getSynapse().setCoefficient(kb.eavNode(true, false, kb.node(Common.symbol), nodeLiteral), -1);
      associateTail.getSynapse().setCoefficient(kb.eavNode(true, false, kb.node(Common.symbol), resolvedIdentifier),
          -1);
      val updateEntrypoint = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).inherit(kb.node(Common.object))
          .literal(kb.node(Common.name), kb.node(Common.entrypoint))
          .transform(kb.node(Common.value), getValueEntrypoint);
      associateTail.then(updateEntrypoint);
    }

    // These nodes can be hyperactivated by their antecedents to enter a refractory
    // period that ensures they are only called once per context.
    val createLiteral = kb.new InvocationNode(kb.node(BuiltIn.node)),
        createTransform = kb.new InvocationNode(kb.node(BuiltIn.node));
    indexNode(parse, createLiteral, "createLiteral");
    indexNode(parse, createTransform, "createTransform");

    // Indicates that term 0 will no longer evolve due to itself or upcoming
    // symbols.
    val forwardStable = new SynapticNode();
    indexNode(parse, forwardStable, "forwardStable");
    {
      val lookaheadOk = new SynapticNode();
      indexNode(forwardStable, lookaheadOk, "lookaheadOk");

      val lookaheadAbsent = new SynapticNode();
      apply.then(lookaheadAbsent);
      lookaheadAbsent.getSynapse().setCoefficient(kb.eavNode(true, true, symbol(1)), -1);
      lookaheadAbsent.then(lookaheadOk);

      forwardStable.conjunction(apply, lookaheadOk);
      forwardStable.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(0), kb.node(Common.codePoint)), -1);
      forwardStable.getSynapse()
          .setCoefficient(kb.eavNode(true, false, symbol(0), identifier.properties.get(kb.node("buffer"))), -1);
      forwardStable.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(0), identifier), -1);
      forwardStable.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(0), memberSelect), -1);
      forwardStable.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(0), indexer), -1);
    }

    val assignment = new SynapticNode();
    indexNode(parse, assignment, "assignment");
    {
      assignment.conjunction(forwardStable, kb.eavNode(true, false, value(-1), kb.node((int) '=')));

      val setProperty = kb.new InvocationNode(kb.node(BuiltIn.node)).literal(kb.node(Common.entrypoint),
          kb.node(BuiltIn.setProperty));
      indexNode(assignment, setProperty, "setProperty");
      assignment.then(setProperty);

      val setLiteral = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), setProperty).literal(kb.node(Common.name), kb.node(Common.literal))
          .transform(kb.node(Common.value), createLiteral);
      setLiteral.conjunction(setProperty, createLiteral);

      val setTransform = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), setProperty).literal(kb.node(Common.name), kb.node(Common.transform))
          .transform(kb.node(Common.value), createTransform);
      setTransform.conjunction(setProperty, createTransform);

      val lhsMemberSelect = kb.eavNode(true, false, symbol(-2), memberSelect);
      val lhsIndexer = kb.eavNode(true, false, symbol(-2), indexer);

      val lhsHasObject = new SynapticNode();
      {
        val lhsCanHaveObject = new SynapticNode();
        lhsMemberSelect.then(lhsCanHaveObject);
        lhsIndexer.then(lhsCanHaveObject);
        lhsHasObject.conjunction(lhsCanHaveObject, kb.eavNode(true, true, symbol(-3)));
        lhsHasObject.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(-3), operator), -1);

        val lhsLiteralObject = new SynapticNode();
        {
          lhsLiteralObject.conjunction(assignment, lhsHasObject, kb.eavNode(true, false, symbol(-3), nodeLiteral));
          lhsLiteralObject.then(createLiteral);
          val setObject = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
              .transform(kb.node(Common.object), createLiteral).literal(kb.node(Common.name), kb.node(Common.object))
              .transform(kb.node(Common.value), value(-3));
          setObject.conjunction(createLiteral, lhsLiteralObject);
        }

        val lhsTransformObject = new SynapticNode();
        {
          lhsTransformObject.conjunction(assignment, lhsHasObject);
          lhsTransformObject.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(-3), nodeLiteral), -1);
          lhsTransformObject.then(createTransform);
          val setObject = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
              .transform(kb.node(Common.object), createTransform).literal(kb.node(Common.name), kb.node(Common.object))
              .transform(kb.node(Common.value), value(-3));
          setObject.conjunction(createTransform, lhsTransformObject);
        }
      }

      val lhsLiteralName = new SynapticNode();
      {
        val lhsLiteralNameSymbol = new SynapticNode();
        kb.eavNode(true, false, symbol(-2), resolvedIdentifier).then(lhsLiteralNameSymbol);
        lhsMemberSelect.then(lhsLiteralNameSymbol);

        lhsLiteralName.conjunction(assignment, lhsLiteralNameSymbol);
        lhsLiteralName.then(createLiteral);
        val setName = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .transform(kb.node(Common.object), createLiteral).literal(kb.node(Common.name), kb.node(Common.name))
            .transform(kb.node(Common.value), value(-2));
        setName.conjunction(createLiteral, lhsLiteralName);
      }

      val lhsTransformName = new SynapticNode();
      {
        lhsTransformName.conjunction(assignment, lhsIndexer);
        lhsTransformName.then(createTransform);
        val setName = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .transform(kb.node(Common.object), createTransform).literal(kb.node(Common.name), kb.node(Common.name))
            .transform(kb.node(Common.value), value(-2));
        setName.conjunction(createTransform, lhsTransformName);
      }

      val rhsIsLiteral = kb.eavNode(true, false, symbol(0), nodeLiteral);

      val rhsLiteral = new SynapticNode();
      {
        rhsLiteral.conjunction(assignment, rhsIsLiteral);
        rhsLiteral.then(createLiteral);
        val setValue = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .transform(kb.node(Common.object), createLiteral).literal(kb.node(Common.name), kb.node(Common.value))
            .transform(kb.node(Common.value), value(0));
        setValue.conjunction(createLiteral, rhsLiteral);
      }

      val rhsTransform = new SynapticNode();
      {
        assignment.then(rhsTransform);
        rhsTransform.getSynapse().setCoefficient(rhsIsLiteral, -1);
        rhsTransform.then(createTransform);
        val setValue = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .transform(kb.node(Common.object), createTransform).literal(kb.node(Common.name), kb.node(Common.value))
            .transform(kb.node(Common.value), value(0));
        setValue.conjunction(createTransform, rhsTransform);
      }

      val initEntrypoint = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), setProperty).literal(kb.node(Common.name), kb.node(Common.entrypoint))
          .transform(kb.node(Common.value), setProperty);
      setProperty.then(initEntrypoint);

      val mergeValueEntrypoint = kb.new InvocationNode(mergeEntrypoint).transform(kb.node(Common.object), setProperty)
          .transform(kb.node(Common.symbol), symbol(0)).transform(kb.node(Common.value), value(0));
      initEntrypoint.then(mergeValueEntrypoint);
      val mergeNameEntrypoint = kb.new InvocationNode(mergeEntrypoint).transform(kb.node(Common.object), setProperty)
          .transform(kb.node(Common.symbol), symbol(-2)).transform(kb.node(Common.value), value(-2));
      mergeValueEntrypoint.then(mergeNameEntrypoint);
      val mergeObjectEntrypoint = kb.new InvocationNode(mergeEntrypoint).transform(kb.node(Common.object), setProperty)
          .transform(kb.node(Common.symbol), symbol(-4)).transform(kb.node(Common.value), value(-4));
      mergeObjectEntrypoint.conjunction(mergeNameEntrypoint, lhsHasObject);

      val rewrite3 = rewrite(3, assignment, setProperty);
      setProperty.then(rewrite3);
      rewrite3.getSynapse().setCoefficient(lhsHasObject, -1);

      rewrite(4, assignment, setProperty).conjunction(setProperty, lhsHasObject);
    }

    final Node index = parse("index");
    // Temporarily use _ to denote local resolution bindings until we can do
    // parse-time baking.
    index.properties.put(kb.node("_createLiteral"), createLiteral);
    index.properties.put(kb.node("_createTransform"), createTransform);
    index.properties.put(kb.node("_v0"), value(0));
    index.properties.put(kb.node("_vn1"), value(-1));
    index.properties.put(kb.node("_vn3"), value(-3));
    index.properties.put(kb.node("_param1"), kb.param(1));
    index.properties.put(kb.node("_arg1"), kb.arg(1));

    val rvalueMember = new SynapticNode();
    indexNode(parse, rvalueMember, "rvalueMember");
    {
      val isMemberSelect = kb.eavNode(true, false, symbol(0), memberSelect);
      val isIndexer = kb.eavNode(true, false, symbol(0), indexer);
      val isMemberSelectOrIndexer = new SynapticNode();
      isMemberSelect.then(isMemberSelectOrIndexer);
      isIndexer.then(isMemberSelectOrIndexer);

      val isLookaheadCodePoint = kb.eavNode(true, false, symbol(1), kb.node(Common.codePoint));

      val isLookaheadWhitespace = kb.new InvocationNode(kb.node(BuiltIn.method))
          .literal(kb.node(Common.javaClass), kb.node(Character.class))
          .literal(kb.node(Common.name), kb.node("isWhitespace")).literal(kb.param(1), kb.node(int.class))
          .transform(kb.arg(1), value(1));
      isLookaheadWhitespace.conjunction(apply, isMemberSelectOrIndexer, isLookaheadCodePoint);

      val isNotLookaheadCodePoint = new SynapticNode();
      apply.then(isNotLookaheadCodePoint);
      isNotLookaheadCodePoint.getSynapse().setCoefficient(isLookaheadCodePoint, -1);

      val lookaheadOk = new SynapticNode();
      indexNode(rvalueMember, lookaheadOk, "lookaheadOk");
      isNotLookaheadCodePoint.then(lookaheadOk);
      lookaheadOk.getSynapse().setCoefficient(kb.eavNode(true, false, value(1), kb.node((int) '=')), -1);
      isLookaheadWhitespace.then(lookaheadOk);
      lookaheadOk.getSynapse().setCoefficient(kb.eavNode(true, false, isLookaheadWhitespace, kb.node(true)), -1);

      rvalueMember.conjunction(apply, isMemberSelectOrIndexer, lookaheadOk);

      val getProperty = kb.new InvocationNode(kb.node(BuiltIn.node)).literal(kb.node(Common.entrypoint),
          kb.node(BuiltIn.getProperty));
      indexNode(rvalueMember, getProperty, "getProperty");
      index.properties.put(kb.node("_getProperty"), getProperty);
      rvalueMember.then(getProperty);

      ((SynapticNode) parse("_getProperty.literal = _createLiteral")).conjunction(getProperty, createLiteral);
      ((SynapticNode) parse("_getProperty.transform = _createTransform")).conjunction(getProperty, createTransform);

      val hasLhs = new SynapticNode();
      hasLhs.conjunction(rvalueMember, kb.eavNode(true, true, symbol(-1)));
      hasLhs.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(-1), operator), -1);

      val isLhsLiteral = kb.eavNode(true, false, symbol(-1), nodeLiteral);

      val lhsLiteral = new SynapticNode();
      {
        lhsLiteral.conjunction(rvalueMember, isLhsLiteral);
        lhsLiteral.then(createLiteral);
        ((SynapticNode) parse("_createLiteral.object = _vn1")).conjunction(createLiteral, lhsLiteral);
      }

      val lhsTransform = new SynapticNode();
      {
        hasLhs.then(lhsTransform);
        lhsTransform.getSynapse().setCoefficient(isLhsLiteral, -1);

        lhsTransform.then(createTransform);
        ((SynapticNode) parse("_createTransform.object = _vn1")).conjunction(createTransform, lhsTransform);
      }

      val rhsLiteral = new SynapticNode();
      {
        rhsLiteral.conjunction(rvalueMember, isMemberSelect);
        rhsLiteral.then(createLiteral);
        ((SynapticNode) parse("_createLiteral.name = _v0")).conjunction(createLiteral, rhsLiteral);
      }

      val rhsTransform = new SynapticNode();
      {
        rhsTransform.conjunction(rvalueMember, isIndexer);
        rhsTransform.then(createTransform);
        ((SynapticNode) parse("_createTransform.name = _v0")).conjunction(createTransform, rhsTransform);
      }

      val initEntrypoint = ((SynapticNode) parse("_getProperty.entrypoint = _getProperty"));
      getProperty.then(initEntrypoint);

      val mergeNameEntrypoint = kb.new InvocationNode(mergeEntrypoint).transform(kb.node(Common.object), getProperty)
          .transform(kb.node(Common.symbol), symbol(0)).transform(kb.node(Common.value), value(0));
      initEntrypoint.then(mergeNameEntrypoint);
      val mergeObjectEntrypoint = kb.new InvocationNode(mergeEntrypoint).transform(kb.node(Common.object), getProperty)
          .transform(kb.node(Common.symbol), symbol(-1)).transform(kb.node(Common.value), value(-1));
      mergeNameEntrypoint.then(mergeObjectEntrypoint);

      rewrite(2, rvalueMember, getProperty).conjunction(getProperty, hasLhs);

      val rewriteSoloIndexer = rewrite(1, rvalueMember, getProperty);
      rewriteSoloIndexer.conjunction(getProperty, rhsTransform);
      rewriteSoloIndexer.getSynapse().setCoefficient(hasLhs, -1);

      val rewriteSoloMember = rewrite(1, resolvedIdentifier, value(0));
      rhsLiteral.then(rewriteSoloMember);
      rewriteSoloMember.getSynapse().setCoefficient(hasLhs, -1);
    }

    // Baking uses `backticks` to evaluate an expression at parse time and bake the
    // result in as a resolved node.
    val bake = new SynapticNode();
    indexNode(parse, bake, "bake");
    {
      bake.conjunction(apply, kb.eavNode(true, false, value(-2), kb.node((int) '`')),
          kb.eavNode(true, false, symbol(0), operator), kb.eavNode(true, false, value(0), kb.node((int) '`')));

      val getEntrypoint = (SynapticNode) parse("_vn1.entrypoint");
      bake.then(getEntrypoint);
      val getResult = parse("[_vn1]");
      val linkResult = kb.new InvocationNode(kb.node(BuiltIn.associate)).transform(kb.node(Common.prior), value(-1))
          .literal(kb.node(Common.posterior), getResult);
      getEntrypoint.then(linkResult);
      val noEntrypoint = kb.eavNode(true, false, getEntrypoint, null);
      linkResult.getSynapse().setCoefficient(noEntrypoint, -1);

      val activate = kb.new InvocationNode(kb.node(BuiltIn.activate)).transform(kb.node(Common.value), getEntrypoint);
      linkResult.then(activate);
      getResult.then(rewrite(3, resolvedIdentifier, getResult));

      // no-execution case
      rewrite(3, resolvedIdentifier, value(-1)).conjunction(getEntrypoint, noEntrypoint);
    }

    // Now we can start removing the resolution hack, at least for members.
    resolvedIdentifier.getSynapse().setCoefficient(kb.eavNode(true, false, value(-1), kb.node((int) '.')), -1);
    // Go ahead and keep resolvedIdentifier as a prior to simplify `backtick`
    // handling.
    memberSelect.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(0), identifier),
        memberSelect.getSynapse().getCoefficient(kb.eavNode(true, false, symbol(0), resolvedIdentifier)));

    val statement = new SynapticNode();
    indexNode(parse, statement, "statement");
    {
      kb.eavNode(true, false, value(1), kb.node((int) ';'))
          .then((SynapticNode) forwardStable.properties.get(kb.node("lookaheadOk")));

      val hasPrevious = kb.eavNode(true, true, symbol(-1));

      statement.conjunction(apply, kb.eavNode(true, false, symbol(0), operator),
          kb.eavNode(true, false, value(0), kb.node((int) ';')));
      val rewrite2 = rewrite(2, statement, value(-1));
      rewrite2.conjunction(statement, hasPrevious);

      // Delete extraneous leading semicolons.
      val rewrite1 = rewrite(1);
      statement.then(rewrite1);
      rewrite1.getSynapse().setCoefficient(hasPrevious, -1);
    }

    val statementSequence = new SynapticNode();
    {
      statementSequence.conjunction(forwardStable, kb.eavNode(true, false, symbol(-1), statement));
      statementSequence
          .then(kb.new InvocationNode(mergeEntrypoint).transform(kb.node(Common.object), value(0))
              .transform(kb.node(Common.symbol), symbol(-1)).transform(kb.node(Common.value), value(-1)))
          .then(rewrite(2, statementSequence, value(0)));
    }

    val getOrCreate = new SynapticNode();
    indexNode(parse, getOrCreate, "getOrCreate");
    {
      val get = (SynapticNode) parse("object[name]");
      indexNode(getOrCreate, get, "get");
      getOrCreate.then(get);
      val absent = kb.eavNode(true, false, get, null);

      val returnGet = (SynapticNode) parse("returnValue = `'parse.getOrCreate.get`");
      get.then(returnGet);
      returnGet.getSynapse().setCoefficient(absent, -1);

      val create = kb.new InvocationNode(kb.node(BuiltIn.node)).inherit(kb.node(Common.entrypoint));
      indexNode(getOrCreate, create, "create");
      create.conjunction(get, absent);
      create.then((SynapticNode) parse("""
          object[name] = `'parse.getOrCreate.create`;
          returnValue = `'parse.getOrCreate.create`;
          """).properties.get(kb.node(Common.entrypoint)));
    }

    val call = new SynapticNode();
    indexNode(parse, call, "call");
    {
      val startCall = new SynapticNode();
      indexNode(call, startCall, "start");
      startCall.conjunction(apply, kb.eavNode(true, false, symbol(-1), resolvedIdentifier),
          kb.eavNode(true, false, symbol(0), operator), kb.eavNode(true, false, value(0), kb.node((int) '(')));

      val invocation = kb.new InvocationNode(kb.node(BuiltIn.node)).transform(kb.node(Common.entrypoint), value(-1));
      indexNode(call, invocation, "invocation");
      startCall.then(invocation);
      invocation.then((SynapticNode) parse("`'parse.call.invocation`.`'entrypoint` = `'parse.call.invocation`"));

      invocation.then(rewrite(2, invocation, invocation));

      val cParen = kb.eavNode(true, false, value(0), kb.node((int) ')')),
          comma = kb.eavNode(true, false, value(0), kb.node((int) ','));

      val noArgs = new SynapticNode();
      noArgs.conjunction(apply, kb.eavNode(true, false, symbol(-1), invocation),
          kb.eavNode(true, false, symbol(0), operator), cParen);
      noArgs.then(rewrite(2, call, value(-1)));

      val argDelimiter = new SynapticNode();
      comma.then(argDelimiter);
      cParen.then(argDelimiter);

      val namedArg = new SynapticNode();
      indexNode(call, namedArg, "namedArg");
      namedArg.conjunction(kb.eavNode(true, false, symbol(-4), invocation),
          kb.eavNode(true, false, value(-2), kb.node((int) ':')), kb.eavNode(true, false, symbol(0), operator),
          argDelimiter);
      namedArg.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(-1), invocation), -1);

      val literalValue = kb.eavNode(true, false, symbol(-1), nodeLiteral);
      ((SynapticNode) parse("argType = 'literal")).conjunction(namedArg, literalValue);
      val asTransform = (SynapticNode) parse("argType = 'transform");
      namedArg.then(asTransform);
      asTransform.getSynapse().setCoefficient(literalValue, -1);

      val getOrCreateArgType = kb.new InvocationNode(getOrCreate).transform(kb.node(Common.object), value(-4))
          .transform(kb.node(Common.name), kb.node("argType"));
      indexNode(call, getOrCreateArgType, "getOrCreateArgType");
      getOrCreateArgType.conjunction(namedArg, kb.eavNode(true, true, kb.node("argType")));
      getOrCreateArgType.then((SynapticNode) parse("`'parse.call.getOrCreateArgType`[_vn3] = _vn1"));

      val mergeArgEntrypoint = kb.new InvocationNode(mergeEntrypoint).transform(kb.node(Common.object), value(-4))
          .transform(kb.node(Common.symbol), symbol(-1)).transform(kb.node(Common.value), value(-1));
      namedArg.then(mergeArgEntrypoint);

      rewrite(5, invocation, value(-4)).conjunction(namedArg, comma);
      rewrite(5, call, value(-4)).conjunction(namedArg, cParen);
    }

    val stringLiteral = new SynapticNode();
    indexNode(parse, stringLiteral, "stringLiteral");
    {
      val buffer = new SynapticNode();
      indexNode(stringLiteral, buffer, "buffer");

      val start = new SynapticNode();
      indexNode(stringLiteral, start, "start");
      {
        start.conjunction(apply, kb.eavNode(true, false, symbol(0), operator),
            kb.eavNode(true, false, value(0), kb.node((int) '"')));
        start.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(-1), buffer), -1);

        val newBuffer = kb.new InvocationNode(kb.node(Bootstrap.newInstance)).literal(kb.node(Common.javaClass),
            kb.node(StringBuilder.class));
        newBuffer.comment = "newBuffer";
        start.then(newBuffer);
        newBuffer.then(rewrite(1, buffer, newBuffer));
      }

      // Suspend other parse rules while a string literal is being parsed.
      val followsBuffer = kb.eavNode(true, false, symbol(-1), buffer);
      operator.getSynapse().setCoefficient(followsBuffer, -1);
      whitespace.getSynapse().setCoefficient(followsBuffer, -1);
      ((SynapticNode) parse("`'parse.identifier.start`")).getSynapse().setCoefficient(followsBuffer, -1);

      val part = new SynapticNode();
      indexNode(stringLiteral, part, "part");
      {
        part.conjunction(isCodePoint, kb.eavNode(true, false, symbol(-1), buffer));
        part.getSynapse().setCoefficient(kb.eavNode(true, false, value(0), kb.node((int) '"')), -1);

        val append = kb.new InvocationNode(kb.node(BuiltIn.method)).transform(kb.node(Common.object), value(-1))
            .literal(kb.node(Common.name), kb.node("appendCodePoint")).literal(kb.param(1), kb.node(int.class))
            .transform(kb.arg(1), value(0));
        part.then(append);

        // Simply delete the trailing code point now that we've folded it into the
        // buffer.
        part.then(rewrite(1));
      }

      val end = new SynapticNode();
      indexNode(stringLiteral, end, "end");
      {
        end.conjunction(apply, kb.eavNode(true, false, symbol(-1), buffer),
            kb.eavNode(true, false, value(0), kb.node((int) '"')));

        val toString = kb.new InvocationNode(kb.node(BuiltIn.method)).transform(kb.node(Common.object), value(-1))
            .literal(kb.node(Common.name), kb.node("toString"));
        end.then(toString);
        toString.then(rewrite(2, nodeLiteral, toString));
      }
    }

    // As the language gets more capable, we can start parsing longer and longer
    // strings so we'll want to reset the recursion limit while progress is being
    // made.
    {
      parse.then((SynapticNode) parse("maxStackDepthOnReset = maxStackDepth"));
      val initLength = parse("""
          `'parse.rewriter`.shortestLength = method(
            object: `'parse.rewriter`,
            name: "length"
          )
          """);
      val rewriter = parse("`'parse.rewriter`");
      rewriter.then((SynapticNode) initLength.properties.get(kb.node(Common.entrypoint)));
      val advance = (SynapticNode) parse("`'parse.advance`");
      initLength.then(advance);
      advance.getSynapse().dissociate(rewriter);

      val invokeApply = parse("`'parse.invokeApply`");
      val recurse = (InvocationNode) parse("`'parse.recurse`");
      recurse.inherit(kb.node("maxStackDepthOnReset"));
      // Temporarily remove the stack depth limit on parse
      recurse.literal(kb.node(Common.maxStackDepth), kb.node(KnowledgeBase.DEFAULT_MAX_STACK_DEPTH));

      val compareLengths = parse("""
          method(
            object: method(
              object: `'parse.rewriter`,
              name: "length"
            ),
            name: "compareTo",
            _param1: '`findClass(name: "java.lang.Integer")`,
            _arg1: `'parse.rewriter`.shortestLength
          )
          """);
      recurse.literal(kb.node(Common.maxStackDepth), null);

      invokeApply.then((SynapticNode) compareLengths.properties.get(kb.node(Common.entrypoint)));
      val shorter = kb.eavNode(true, false, compareLengths, kb.node(-1));

      // If the length got shorter, first reset the max stack depth.
      shorter.then((SynapticNode) parse("maxStackDepth = maxStackDepthOnReset")).then(recurse);
      compareLengths.then(recurse);
      recurse.getSynapse().setCoefficient(shorter, -1);
      recurse.getSynapse().dissociate(invokeApply);
    }

    val intLiteral = new SynapticNode();
    indexNode(parse, intLiteral, "intLiteral");
    {
      val builder = new SynapticNode();
      indexNode(intLiteral, builder, "builder");

      val preCheck = new SynapticNode();
      indexNode(intLiteral, preCheck, "preCheck");
      isCodePoint.then(preCheck);
      preCheck.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(-1), parse("'parse.identifier.buffer")), -1);
      preCheck.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(-1), parse("'parse.stringLiteral.buffer")),
          -1);

      val isDigit = (SynapticNode) parse("""
          method(
            javaClass: '`findClass(name: "java.lang.Character")`,
            name: "isDigit",
            _param1: '`findClass(name: "int")`,
            _arg1: _v0
          )
          """);
      preCheck.then(isDigit);

      val isReallyDigit = new SynapticNode();
      isReallyDigit.conjunction(preCheck, kb.eavNode(true, false, isDigit, kb.node(true)));

      val followsBuilder = kb.eavNode(true, false, symbol(-1), builder);

      val start = new SynapticNode();
      indexNode(intLiteral, start, "start");
      {
        isReallyDigit.then(start);
        start.getSynapse().setCoefficient(followsBuilder, -1);
        start.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(-1), parse("`'parse.identifier.buffer`")), -1);
        start.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(-1), parse("`'parse.stringLiteral.buffer`")),
            -1);

        val newBuilder = (SynapticNode) parse("newInstance(javaClass: '`findClass(name: \"ai.xng.IntBuilder\")`)");
        indexNode(start, newBuilder, "newBuilder");
        start.then(newBuilder);
        val append = (SynapticNode) parse("""
            method(
              object: `'parse.intLiteral.start.newBuilder`,
              name: "append",
              _param1: '`findClass(name: "int")`,
              _arg1: _v0
            )
            """);
        newBuilder.then(append);
        newBuilder.then(rewrite(1, builder, newBuilder));
      }

      forwardStable.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(0), builder), -1);

      val part = new SynapticNode();
      indexNode(intLiteral, part, "part");
      {
        part.conjunction(isReallyDigit, followsBuilder);

        val append = (SynapticNode) parse("""
            method(
              object: _vn1,
              name: "append",
              _param1: '`findClass(name: "int")`,
              _arg1: _v0
            )
            """);
        part.then(append);
        // Simply delete the trailing code point now that we've folded it into the
        // builder.
        part.then(rewrite(1));
      }

      val end = new SynapticNode();
      indexNode(intLiteral, end, "end");
      {
        end.conjunction(apply, kb.eavNode(true, false, symbol(0), builder));
        // We can get away with this simple inhibition because any non-digit character
        // coming up will be consumed as an identifier start, whitespace, or an
        // operator, after which we'll backtrack to here.
        end.getSynapse().setCoefficient(kb.eavNode(true, false, symbol(1), kb.node(Common.codePoint)), -1);

        val get = (SynapticNode) parse("method(object: _v0, name: \"get\")");
        end.then(get);
        get.then(rewrite(1, nodeLiteral, get));
      }
    }
  }
}
