package ai.xng;

import ai.xng.KnowledgeBase.Bootstrap;
import ai.xng.KnowledgeBase.BuiltIn;
import ai.xng.KnowledgeBase.Common;
import lombok.val;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LanguageBootstrap {
  public static void indexNode(final KnowledgeBase kb, final Node index, final Node node, final String name) {
    node.comment = name;
    index.properties.put(kb.node(name), node);
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

      notStarted.synapse.setCoefficient(onNext, 1);
      notStarted.synapse.setCoefficient(hasBuffer, -1);

      val hadBuffer = new SynapticNode();
      hadBuffer.synapse.setCoefficient(hasBuffer, 1);
      hadBuffer.synapse.setCoefficient(onNext, -1);
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
      noValue.synapse.setCoefficient(inProgress, 1);
      noValue.synapse.setCoefficient(hasValue, -1);
      noValue.then(collect);
    }

    val resolvedIdentifier = new SynapticNode();
    indexNode(kb, parse, resolvedIdentifier, "resolvedIdentifier");
    {
      val getIdentifier = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), identifier);
      val unset = kb.eavNode(true, false, state, identifier, null);
      val set = kb.eavNode(true, true, state, identifier);
      val notAlreadySet = new SynapticNode();
      notAlreadySet.synapse.setCoefficient(set, -1);
      notAlreadySet.synapse.setCoefficient(onNext, 1);
      getIdentifier.conjunction(notAlreadySet, set);
      getIdentifier.synapse.setCoefficient(unset, -1);
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
      returnResolved.synapse.setCoefficient(kb.eavNode(true, false, getResolvedIdentifier, null), -1);
    }

    val reference = new SynapticNode();
    indexNode(kb, parse, reference, "reference");
    {
      val ifAmp = kb.eavNode(true, false, kb.node(Common.value), kb.node((int) '&'));
      val set = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), reference).literal(kb.node(Common.value), reference);
      set.conjunction(ifAmp, identifier.properties.get(kb.node("notStarted")));
      set.synapse.setCoefficient(kb.eavNode(true, true, state, identifier), -1);
      set.synapse.setCoefficient(kb.eavNode(true, true, state, reference), -1);

      val unset = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), reference);
      unset.conjunction(onNext, kb.eavNode(true, false, state, identifier, null));
    }

    val assignment = new SynapticNode();
    indexNode(kb, parse, assignment, "assignment");
    {
      val ifEq = kb.eavNode(true, false, kb.node(Common.value), kb.node((int) '='));
      val startAssignment = new SynapticNode();
      startAssignment.conjunction(onNext, ifEq, kb.eavNode(true, true, state, resolvedIdentifier));

      val setProperty = kb.new InvocationNode(kb.node(BuiltIn.node)).literal(kb.node(Common.entrypoint),
          kb.node(BuiltIn.setProperty));
      setProperty.comment = "setProperty";
      {
        val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
            .literal(kb.node(Common.name), setProperty).transform(kb.node(Common.value), setProperty);
        setProperty.then(publish);
      }
      startAssignment.then(setProperty);
      val literal = kb.new InvocationNode(kb.node(BuiltIn.node));
      setProperty.then(literal);
      val setLiteral = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), setProperty).literal(kb.node(Common.name), kb.node(Common.literal))
          .transform(kb.node(Common.value), literal);
      literal.then(setLiteral);
      val consumeResolved = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), resolvedIdentifier);
      {
        val resetIdentifier = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .transform(kb.node(Common.object), state).literal(kb.node(Common.name), identifier);
        consumeResolved.then(resetIdentifier);
      }
      setProperty.then(consumeResolved);
      val setName = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), literal)
          .literal(kb.node(Common.name), kb.node(Common.name)).transform(kb.node(Common.value), consumeResolved);
      setName.conjunction(setLiteral, consumeResolved);

      val rhs = new SynapticNode();
      val hadSetProperty = new SynapticNode();
      hadSetProperty.synapse.setCoefficient(kb.eavNode(true, true, state, setProperty), 1);
      hadSetProperty.synapse.setCoefficient(onNext, -1);
      rhs.conjunction(onNext, hadSetProperty, kb.eavNode(true, true, state, resolvedIdentifier));
      rhs.synapse.setCoefficient(kb.eavNode(true, false, state, resolvedIdentifier, null), -1);
      rhs.then(consumeResolved);

      val getSetProperty = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), setProperty);
      {
        val publish = kb.new InvocationNode(kb.node(BuiltIn.setProperty)).transform(kb.node(Common.object), state)
            .literal(kb.node(Common.name), assignment).transform(kb.node(Common.value), getSetProperty);
        getSetProperty.then(publish);
        val resetSetProperty = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
            .transform(kb.node(Common.object), state).literal(kb.node(Common.name), setProperty);
        getSetProperty.then(resetSetProperty);
      }
      rhs.then(getSetProperty);

      val isRef = kb.eavNode(true, false, state, reference, reference);

      val getLiteral = kb.new InvocationNode(kb.node(BuiltIn.getProperty))
          .transform(kb.node(Common.object), getSetProperty).literal(kb.node(Common.name), kb.node(Common.literal));
      val setLiteralValue = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), getLiteral).literal(kb.node(Common.name), kb.node(Common.value))
          .transform(kb.node(Common.value), consumeResolved);
      getLiteral.conjunction(getSetProperty, isRef);
      setLiteralValue.conjunction(getLiteral, consumeResolved);

      val transform = kb.new InvocationNode(kb.node(BuiltIn.node));
      transform.synapse.setCoefficient(rhs, 1);
      transform.synapse.setCoefficient(isRef, -1);
      val setTransform = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), getSetProperty).literal(kb.node(Common.name), kb.node(Common.transform))
          .transform(kb.node(Common.value), transform);
      setTransform.conjunction(getSetProperty, transform);
      val setTransformValue = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .transform(kb.node(Common.object), transform).literal(kb.node(Common.name), kb.node(Common.value))
          .transform(kb.node(Common.value), consumeResolved);
      setTransformValue.conjunction(setTransform, consumeResolved);

      val getAssignment = kb.new InvocationNode(kb.node(BuiltIn.getProperty)).transform(kb.node(Common.object), state)
          .literal(kb.node(Common.name), assignment);
      collectResult.then(getAssignment);
      val returnAssignment = kb.new InvocationNode(kb.node(BuiltIn.setProperty))
          .literal(kb.node(Common.name), kb.node(Common.returnValue)).transform(kb.node(Common.value), getAssignment);
      getAssignment.then(returnAssignment);
      returnAssignment.synapse.setCoefficient(kb.eavNode(true, false, getAssignment, null), -1);
    }
  }
}