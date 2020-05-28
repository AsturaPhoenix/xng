package ai.xng;

import ai.xng.KnowledgeBase.Bootstrap;
import ai.xng.KnowledgeBase.BuiltIn;
import ai.xng.KnowledgeBase.Common;
import ai.xng.KnowledgeBase.InvocationNode;
import lombok.val;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LanguageBootstrap {
  public static void indexNode(final KnowledgeBase kb, final Node index, final Node node, final String name) {
    node.comment = name;
    index.properties.put(kb.node(name), node);
  }

  public static Node parse(final KnowledgeBase kb, final String string) {
    val context = kb.newContext();
    context.fatalOnExceptions();
    // Although naively we could activate parse directly, the easiest way to extract
    // a return value from a tail recursed subroutine is through an invocation node.
    val parse = kb.new InvocationNode(kb.node(Bootstrap.parse)).literal(kb.node(Common.value), kb.node(string));
    parse.activate(context);
    context.blockUntilIdle();
    return context.node.properties.get(parse);
  }

  /**
   * Expand eval/parse into a self-sustaining language.
   */
  public static void bootstrap(final KnowledgeBase kb) {
    val parse = kb.node(Bootstrap.parse);
    val state = parse.properties.get(kb.node("state"));
    val onNext = parse.properties.get(kb.node("onNext"));
    val collectResult = parse.properties.get(kb.node("collectResult"));

    val hasValue = kb.eavNode(true, true, kb.node(Common.value));

    val identifier = new SynapticNode();
    indexNode(kb, parse, identifier, "identifier");
    {
      val buffer = kb.new InvocationNode(kb.node(Bootstrap.newInstance)).literal(kb.node(Common.javaClass),
          kb.node(StringBuilder.class));
      buffer.comment = "buffer";
      {
        val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
            .literal(kb.node(Common.name), buffer).transform(kb.node(Common.value), buffer);
        buffer.then(publish);
      }

      val getBuffer = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), buffer);
      val cacheBuffer = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).literal(kb.node(Common.name), buffer)
          .transform(kb.node(Common.value), getBuffer);
      getBuffer.then(cacheBuffer);

      val notStarted = new SynapticNode(), inProgress = new SynapticNode();
      indexNode(kb, identifier, notStarted, "notStarted");
      indexNode(kb, identifier, inProgress, "inProgress");

      val hasBuffer = kb.eavNode(true, true, state, buffer);

      notStarted.getSynapse().setCoefficient(onNext, 1);
      notStarted.getSynapse().setCoefficient(hasBuffer, -1);

      val hadBuffer = new SynapticNode();
      hadBuffer.getSynapse().setCoefficient(hasBuffer, 1);
      hadBuffer.getSynapse().setCoefficient(onNext, -1);
      getBuffer.conjunction(onNext, hadBuffer);
      cacheBuffer.then(inProgress);

      // Transcribe identifier character
      val ifIdentifierStart = kb.new InvocationNode(kb.node(BuiltIn.method))
          .literal(kb.node(Common.javaClass), kb.node(Character.class))
          .literal(kb.node(Common.name), kb.node("isJavaIdentifierStart")).literal(kb.param(1), kb.node(int.class))
          .transform(kb.arg(1), kb.node(Common.value));
      ifIdentifierStart.conjunction(notStarted, hasValue);
      kb.eavNode(true, false, ifIdentifierStart, kb.node(true)).then(buffer);

      val append = kb.new InvocationNode(kb.node(BuiltIn.method)).transform(kb.node(Common.object), buffer)
          .literal(kb.node(Common.name), kb.node("appendCodePoint")).literal(kb.param(1), kb.node(int.class))
          .transform(kb.arg(1), kb.node(Common.value));
      buffer.then(append);

      val ifIdentifierPart = kb.new InvocationNode(kb.node(BuiltIn.method))
          .literal(kb.node(Common.javaClass), kb.node(Character.class))
          .literal(kb.node(Common.name), kb.node("isJavaIdentifierPart")).literal(kb.param(1), kb.node(int.class))
          .transform(kb.arg(1), kb.node(Common.value));
      ifIdentifierPart.conjunction(inProgress, hasValue);
      kb.eavNode(true, false, ifIdentifierPart, kb.node(true)).then(append);

      val collect = kb.new InvocationNode(kb.node(BuiltIn.method)).transform(kb.node(Common.object), buffer)
          .literal(kb.node(Common.name), kb.node("toString"));
      val resetBuffer = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), buffer);
      collect.then(resetBuffer);
      {
        val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
            .literal(kb.node(Common.name), identifier).transform(kb.node(Common.value), collect);
        collect.then(publish);
      }

      kb.eavNode(true, false, ifIdentifierPart, kb.node(false)).then(collect);
      val noValue = new SynapticNode();
      noValue.getSynapse().setCoefficient(inProgress, 1);
      noValue.getSynapse().setCoefficient(hasValue, -1);
      noValue.then(collect);
    }

    val resetIdentifier = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
        .literal(kb.node(Common.name), identifier);
    indexNode(kb, identifier, resetIdentifier, "reset");

    val resolvedIdentifier = new SynapticNode();
    indexNode(kb, parse, resolvedIdentifier, "resolvedIdentifier");
    {
      val getIdentifier = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), identifier);
      val unset = kb.eavNode(true, false, state, identifier, null);
      val set = kb.eavNode(true, true, state, identifier);
      val notAlreadySet = new SynapticNode();
      notAlreadySet.getSynapse().setCoefficient(set, -1);
      notAlreadySet.getSynapse().setCoefficient(onNext, 1);
      getIdentifier.conjunction(notAlreadySet, set);
      getIdentifier.getSynapse().setCoefficient(unset, -1);
      val resolve = kb.new InvocationNode(kb.node(Bootstrap.resolve)).transform(kb.node(Common.name), getIdentifier);
      getIdentifier.then(resolve);
      {
        val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
            .literal(kb.node(Common.name), resolvedIdentifier).transform(kb.node(Common.value), resolve);
        resolve.then(publish);
      }

      val remove = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), resolvedIdentifier);
      remove.conjunction(onNext, unset);

      val getResolvedIdentifier = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
          .transform(kb.node(Common.object), state).literal(kb.node(Common.name), resolvedIdentifier);
      collectResult.then(getResolvedIdentifier);
      val returnResolved = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .literal(kb.node(Common.name), kb.node(Common.returnValue))
          .transform(kb.node(Common.value), getResolvedIdentifier);
      getResolvedIdentifier.then(returnResolved);
      returnResolved.getSynapse().setCoefficient(kb.eavNode(true, false, getResolvedIdentifier, null), -1);
    }

    val consumeResolved = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
        .literal(kb.node(Common.name), resolvedIdentifier);
    indexNode(kb, resolvedIdentifier, consumeResolved, "consume");
    consumeResolved.then(resetIdentifier);

    val reference = new SynapticNode();
    indexNode(kb, parse, reference, "reference");
    {
      val ifAmp = kb.eavNode(true, false, kb.node(Common.value), kb.node((int) '&'));
      val set = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), reference).literal(kb.node(Common.value), reference);
      set.conjunction(ifAmp, identifier.properties.get(kb.node("notStarted")));
      set.getSynapse().setCoefficient(kb.eavNode(true, true, state, identifier), -1);
      set.getSynapse().setCoefficient(kb.eavNode(true, true, state, reference), -1);

      val unset = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), reference);
      unset.conjunction(onNext, kb.eavNode(true, false, state, identifier, null));
    }

    val assignment = new SynapticNode();
    indexNode(kb, parse, assignment, "assignment");
    {
      val ifEq = kb.eavNode(true, false, kb.node(Common.value), kb.node((int) '='));
      val startAssignment = new SynapticNode();
      indexNode(kb, assignment, startAssignment, "start");
      startAssignment.conjunction(onNext, ifEq, kb.eavNode(true, true, state, resolvedIdentifier));

      val setProperty = kb.new InvocationNode(kb.node(BuiltIn.node)).literal(kb.node(Common.entrypoint),
          kb.node(BuiltIn.setProperty));
      indexNode(kb, assignment, setProperty, "setProperty");
      {
        val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
            .literal(kb.node(Common.name), assignment).transform(kb.node(Common.value), setProperty);
        setProperty.then(publish);
      }
      startAssignment.then(setProperty);
      val literal = kb.new InvocationNode(kb.node(BuiltIn.node));
      setProperty.then(literal);
      val setLiteral = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), setProperty).literal(kb.node(Common.name), kb.node(Common.literal))
          .transform(kb.node(Common.value), literal);
      literal.then(setLiteral);
      setProperty.then(consumeResolved);
      val setName = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), literal)
          .literal(kb.node(Common.name), kb.node(Common.name)).transform(kb.node(Common.value), consumeResolved);
      setName.conjunction(setLiteral, consumeResolved);

      val rhs = new SynapticNode();
      val hadAssignment = new SynapticNode();
      hadAssignment.getSynapse().setCoefficient(kb.eavNode(true, true, state, assignment), 1);
      hadAssignment.getSynapse().setCoefficient(onNext, -1);
      rhs.conjunction(onNext, hadAssignment, kb.eavNode(true, true, state, resolvedIdentifier));
      rhs.getSynapse().setCoefficient(kb.eavNode(true, false, state, resolvedIdentifier, null), -1);
      rhs.then(consumeResolved);

      val getAssignment = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), assignment);
      rhs.then(getAssignment);

      val isRef = kb.eavNode(true, false, state, reference, reference);

      val getLiteral = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
          .transform(kb.node(Common.object), getAssignment).literal(kb.node(Common.name), kb.node(Common.literal));
      val setLiteralValue = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), getLiteral).literal(kb.node(Common.name), kb.node(Common.value))
          .transform(kb.node(Common.value), consumeResolved);
      getLiteral.conjunction(rhs, getAssignment, isRef);
      setLiteralValue.conjunction(getLiteral, consumeResolved);

      val transform = kb.new InvocationNode(kb.node(BuiltIn.node));
      transform.getSynapse().setCoefficient(rhs, 1);
      transform.getSynapse().setCoefficient(isRef, -1);
      val setTransform = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), getAssignment).literal(kb.node(Common.name), kb.node(Common.transform))
          .transform(kb.node(Common.value), transform);
      setTransform.conjunction(rhs, getAssignment, transform);
      val setTransformValue = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), transform).literal(kb.node(Common.name), kb.node(Common.value))
          .transform(kb.node(Common.value), consumeResolved);
      setTransformValue.conjunction(setTransform, consumeResolved);

      collectResult.then(getAssignment);
      val returnAssignment = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .literal(kb.node(Common.name), kb.node(Common.returnValue)).transform(kb.node(Common.value), getAssignment);
      returnAssignment.conjunction(collectResult, getAssignment);
      returnAssignment.getSynapse().setCoefficient(kb.eavNode(true, false, getAssignment, null), -1);
    }

    val getOrCreate = new SynapticNode();
    getOrCreate.comment = "getOrCreate";
    {
      val get = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).inherit(kb.node(Common.object))
          .inherit(kb.node(Common.name));
      indexNode(kb, getOrCreate, get, "get");
      getOrCreate.then(get);
      val absent = kb.eavNode(true, false, get, null);

      val returnGet = ((InvocationNode) parse(kb, "returnValue = ")).transform(kb.node(Common.value), get);
      returnGet.getSynapse().setCoefficient(get, 1);
      returnGet.getSynapse().setCoefficient(absent, -1);

      val create = kb.new InvocationNode(kb.node(BuiltIn.node));
      indexNode(kb, getOrCreate, create, "create");
      create.conjunction(get, absent);
      val set = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).inherit(kb.node(Common.object))
          .inherit(kb.node(Common.name)).transform(kb.node(Common.value), create);
      create.then(set);
      val returnCreate = ((InvocationNode) parse(kb, "returnValue = ")).transform(kb.node(Common.value), create);
      set.then(returnCreate);
    }

    val call = new SynapticNode();
    indexNode(kb, parse, call, "call");
    {
      val ifOparen = kb.eavNode(true, false, kb.node(Common.value), kb.node((int) '('));
      val startCall = new SynapticNode();
      indexNode(kb, call, startCall, "start");
      val hasResolvedIdentifier = kb.eavNode(true, true, state, resolvedIdentifier);
      startCall.conjunction(onNext, ifOparen, hasResolvedIdentifier);

      startCall.then(consumeResolved);

      val invocation = kb.new InvocationNode(kb.node(BuiltIn.node)).transform(kb.node(Common.entrypoint),
          consumeResolved);
      indexNode(kb, call, invocation, "invocation");
      invocation.conjunction(startCall, consumeResolved);
      {
        val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
            .literal(kb.node(Common.name), call).transform(kb.node(Common.value), invocation);
        invocation.then(publish);
      }

      val hasCall = kb.eavNode(true, true, state, call);
      val ifCparen = kb.eavNode(true, false, kb.node(Common.value), kb.node((int) ')'));
      val endCall = new SynapticNode();
      indexNode(kb, call, endCall, "end");
      endCall.conjunction(onNext, ifCparen, hasCall);
      endCall.getSynapse().setCoefficient(kb.eavNode(true, false, state, invocation, null), -1);

      // If we encounter '=', pre-empt assignment and treat as named args instead.
      // Named args behave pretty differently from assignment. Assignment would create
      // a setProperty invocation that sets the named property, but named args can't
      // do that since the only thing it could set properties on are the
      // literal/transform properties of the invocation, which is a non-contextual
      // structural modification we should avoid here.
      val startAssignment = (SynapticNode) assignment.properties.get(kb.node("start"));
      startAssignment.getSynapse().setCoefficient(hasCall, -1);

      val namedArg = new SynapticNode();
      indexNode(kb, call, namedArg, "namedArg");
      namedArg.conjunction(onNext, hasCall, kb.eavNode(true, false, kb.node(Common.value), kb.node((int) '=')),
          hasResolvedIdentifier);
      namedArg.then(consumeResolved);
      val primeNamedArg = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), namedArg).transform(kb.node(Common.value), consumeResolved);
      primeNamedArg.conjunction(namedArg, consumeResolved);

      val setNamedArg = new SynapticNode();
      indexNode(kb, call, setNamedArg, "setNamedArg");
      {
        val hasNamedArg = kb.eavNode(true, true, state, namedArg);
        val hadNamedArg = new SynapticNode();
        hadNamedArg.getSynapse().setCoefficient(hasNamedArg, 1);
        hadNamedArg.getSynapse().setCoefficient(onNext, -1);
        setNamedArg.conjunction(onNext, hadNamedArg, hasResolvedIdentifier);

        // Suppress endCall until we've processed the arg.
        endCall.getSynapse().setCoefficient(hasNamedArg, -1);

        val getCall = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
            .literal(kb.node(Common.name), call);

        setNamedArg.then(getCall);

        val consumeNamedArg = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
            .transform(kb.node(Common.object), state).literal(kb.node(Common.name), namedArg);
        setNamedArg.then(consumeNamedArg);
        {
          val reset = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
              .literal(kb.node(Common.name), namedArg);
          consumeNamedArg.then(reset);
        }

        setNamedArg.then(consumeResolved);

        val isRef = kb.eavNode(true, false, state, reference, reference);
        val asLiteral = (SynapticNode) parse(kb, "argType = &literal"),
            asTransform = (SynapticNode) parse(kb, "argType = &transform");
        asLiteral.conjunction(setNamedArg, isRef);
        asTransform.getSynapse().setCoefficient(setNamedArg, 1);
        asTransform.getSynapse().setCoefficient(isRef, -1);

        val getOrCreateArgType = kb.new InvocationNode(getOrCreate).transform(kb.node(Common.object), getCall)
            .transform(kb.node(Common.name), kb.node("argType"));
        getOrCreateArgType.conjunction(setNamedArg, getCall, kb.eavNode(true, true, kb.node("argType")));
        val setArg = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .transform(kb.node(Common.object), getOrCreateArgType).transform(kb.node(Common.name), consumeNamedArg)
            .transform(kb.node(Common.value), consumeResolved);
        setArg.conjunction(getOrCreateArgType, consumeNamedArg, consumeResolved);

        // Undo suppression from above.
        endCall.getSynapse().setCoefficient(setArg, 1);
      }

      val getCall = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), call);
      collectResult.then(getCall);
      val returnCall = ((InvocationNode) parse(kb, "returnValue = ")).transform(kb.node(Common.value), getCall);
      getCall.then(returnCall);
      returnCall.getSynapse().setCoefficient(kb.eavNode(true, false, getCall, null), -1);
    }
  }
}