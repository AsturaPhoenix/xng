package ai.xng;

import static ai.xng.KnowledgeBase.POP_FACTOR;
import static ai.xng.KnowledgeBase.PUSH_FACTOR;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import ai.xng.constructs.BooleanDecoder;
import ai.xng.constructs.CharacterDecoder;
import ai.xng.constructs.CoincidentEffect;
import ai.xng.constructs.Latch;
import lombok.AllArgsConstructor;
import lombok.val;

public class LanguageBootstrap {
  private final KnowledgeBase kb;

  private <H extends Prior> Sequence<H> asSequence(final H start) {
    return new Sequence<>(start, start);
  }

  @AllArgsConstructor
  private class Sequence<H extends Node> {
    H head;
    Prior tail;

    public Sequence<H> thenDirect(final BiNode next) {
      tail.then(next);
      return new Sequence<>(head, next);
    }

    public Sequence<H> then(final Posterior... p) {
      tail = tail.then(kb.execution.new Node());
      for (val e : p) {
        tail.then(e);
      }
      return this;
    }

    public Sequence<H> thenDelay() {
      tail = tail.then(kb.execution.new Node());
      return this;
    }

    /**
     * Produces a chain of nodes that spans roughly {@code period} between head and
     * tail activation.
     */
    public Sequence<H> thenDelay(final long period) {
      final long dt = IntegrationProfile.TRANSIENT.defaultInterval();
      for (long t = 0; t < period; t += dt) {
        tail = tail.then(kb.execution.new Node());
      }
      return this;
    }
  }

  private class Spawn {
    final ActionCluster.Node gated = kb.actions.new Node(() -> kb.gated.input.new Node().activate()),
        stateRecognition = kb.actions.new Node(() -> kb.stateRecognition.new Node().activate()),
        sequenceRecognition = kb.actions.new Node(() -> kb.sequenceRecognition.new Node().activate()),
        data = kb.actions.new Node(() -> kb.data.new MutableNode<>().activate());
  }

  private final Spawn spawn;

  private class Control {
    final ImmutableList<Cluster.PriorClusterProfile> frameFieldPriors = new Cluster.PriorClusterProfile.ListBuilder()
        .add(kb.gated.output)
        .add(kb.naming)
        .build();

    final StmCluster stackFrame = new StmCluster("stackFrame"),
        returnValue = new StmCluster("returnValue"),
        cxt = new StmCluster("cxt"),
        tmp = new StmCluster("tmp");
    final BiCluster.Node invocation = kb.naming.new Node("invocation"),
        arg1 = kb.naming.new Node("arg1"),
        returnTo = kb.naming.new Node("returnTo");
    final BiCluster.Node execute = kb.entrypoint.new Node("execute"),
        doReturn = kb.entrypoint.new Node("doReturn");

    {
      // TODO: consider inheriting more from static frames and creating an instance
      // differentiator here so that we can allocate new data onto static frames (or
      // leaving that as a caller responsibility).
      asSequence(execute)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(stackFrame.address)
          .then(invocation)
          .then(kb.gated.gate);

      asSequence(doReturn)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(stackFrame.address)
          .then(returnTo, kb.scalePosteriors(stackFrame, POP_FACTOR))
          .then(
              kb.disassociate(stackFrame, kb.gated.output),
              kb.disassociate(stackFrame, kb.naming))
          .then(kb.gated.gate);
    }
  }

  private final Control control;

  private class StringIterator {
    final BiNode create;
    /**
     * Node activated once a code point has been decoded.
     */
    final BiNode onNext;
    /**
     * Node that should be called once a longer processing operation is ready to
     * advance the iterator. It can also be inhibited by paths that are not ready to
     * proceed.
     */
    final BiNode advance;
    final BooleanDecoder hasNextDecoder;
    final BiNode codePoint;
    final CharacterDecoder charDecoder;
    final InputCluster charCluster;

    {
      val iterator = kb.naming.new Node();
      codePoint = kb.naming.new Node();
      create = kb.execution.new Node();
      advance = kb.execution.new Node();

      val iterator_in = new CoincidentEffect.Curry<>(kb.actions, kb.data),
          iterator_out = new CoincidentEffect.Curry<>(kb.actions, kb.data);

      asSequence(create)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(iterator)
          .then(spawn.data)
          .then(kb.associate(control.frameFieldPriors, kb.data))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(control.arg1)
          .then(iterator_in.node)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(iterator)
          .then(iterator_out.node)
          .then(kb.actions.new Node(() -> ((DataCluster.MutableNode<Object>) iterator_out.require()).setData(
              (((String) iterator_in.require().getData()).codePoints().iterator()))))

          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(codePoint)
          .then(spawn.data)
          .then(kb.associate(control.frameFieldPriors, kb.data))

          .then(advance);

      hasNextDecoder = new BooleanDecoder(kb.actions, kb.data, kb.input,
          data -> data instanceof Iterator<?>i ? Optional.of(i.hasNext()) : Optional.empty());
      asSequence(advance)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(iterator)
          .then(hasNextDecoder.node);

      charCluster = new InputCluster();
      charDecoder = new CharacterDecoder(kb.actions, kb.data, charCluster);
      onNext = kb.execution.new Node();

      val next_in = new CoincidentEffect.Curry<>(kb.actions, kb.data),
          next_out = new CoincidentEffect.Curry<>(kb.actions, kb.data);

      asSequence(hasNextDecoder.isTrue)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(iterator)
          .then(next_in.node)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(codePoint)
          .then(next_out.node)
          .then(kb.actions.new Node(() -> ((DataCluster.MutableNode<Integer>) next_out.require()).setData(
              ((Iterator<Integer>) next_in.require().getData()).next())))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(codePoint)
          .then(charDecoder.node)
          .then(onNext)
          // TODO: We might consider binding codePoint to a register first to avoid
          // polluted state during recognition.
          // Advance by default unless inhibited.
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .thenDirect(advance);
    }
  }

  private final StringIterator stringIterator;

  private class RecognitionClass {
    final BiCluster.Node character = kb.stateRecognition.new Node();

    {
      // character recognition capture
      // We expect recognized characters to trigger a recognition tag two nodes deep,
      // with the first being the capture itself.
      val captureDispatch = stringIterator.onNext
          .then(kb.execution.new Node())
          .then(kb.execution.new Node()) // recognition would trigger here
          .then(kb.execution.new Node());
      captureDispatch.inhibitor(character);
      captureDispatch.then(kb.actions.new Node(() -> {
        val capture = kb.stateRecognition.new Node();
        capture.then(character);
        capture.activate();
      }));
      captureDispatch
          .then(kb.execution.new Node())
          .then(kb.associate(
              Arrays.asList(new Cluster.PriorClusterProfile(stringIterator.charCluster, IntegrationProfile.TRANSIENT)),
              kb.stateRecognition));
    }
  }

  private final RecognitionClass recognitionClass;

  /**
   * This class modifies the InputIterator to capture a recognition conjunction
   * for every frame while active. It does not itself form an association from the
   * captured recognition.
   * <p>
   * Typical usage of this utility is to immediately bind the captured recognition
   * to a recognition circuit, which includes a familiarity tag, semantics, and
   * binding.
   */
  private class RecognitionSequenceMemorizer {
    final BiNode invocation;

    {
      invocation = kb.entrypoint.new Node("rsm");
      val gatedInvocation = kb.gated.input.new Node("rsm");
      invocation.then(gatedInvocation);
      asSequence(gatedInvocation.output)
          // TODO: It may be more idiomatic to create the capture later, but with current
          // limitations that would not allow us to bind to STM while also binding to the
          // captured sequence.
          .then(control.returnValue.address, kb.suppressPosteriors(control.returnValue))
          .then(spawn.stateRecognition, kb.clearPosteriors(control.returnValue))
          .then(kb.associate(control.returnValue, kb.stateRecognition))
          .then(stringIterator.create);

      // This is shared with String LiteralBuilder
      recognitionClass.character.then(control.stackFrame.address, control.invocation);

      // Hook sequence capture up after character capture to avoid dealing with the
      // input conjunction directly. Furthermore, some character types may change the
      // latch state.
      val capture = kb.execution.new Node();
      capture.conjunction(recognitionClass.character, invocation);
      asSequence(capture)
          .then(spawn.sequenceRecognition)
          .then(kb.associate(
              new Cluster.PriorClusterProfile.ListBuilder()
                  .add(kb.sequenceRecognition, IntegrationProfile.TWOGRAM)
                  .add(kb.stateRecognition).build(),
              kb.sequenceRecognition));

      val captureReturn = kb.execution.new Node();
      stringIterator.hasNextDecoder.isFalse.then(control.invocation);
      captureReturn.conjunction(stringIterator.hasNextDecoder.isFalse, invocation);
      asSequence(captureReturn)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.returnValue.address)
          .thenDelay()
          .then(kb.associate(new Cluster.PriorClusterProfile.ListBuilder()
              .baseProfiles(IntegrationProfile.TWOGRAM)
              .add(kb.sequenceRecognition)
              .build(), kb.stateRecognition))
          .then(control.doReturn);
    }
  }

  public final RecognitionSequenceMemorizer recognitionSequenceMemorizer;

  private class Parse {
    final BiNode invocation = kb.entrypoint.new Node("parse");

    final BiCluster.Node constructionPointer = kb.naming.new Node("constructionPointer"),
        writePointer = kb.naming.new Node("writePointer");

    {
      val gatedInvocation = kb.gated.input.new Node("parse");
      // One of the first things we should do when we begin parsing something is start
      // constructing a stack frame.
      //
      // We create it as a gated node so that we can flip it into a register for
      // unambiguous writing while it itself is scoped to the stack frame invoking
      // parse. However, during use, it need not be gated so we can put the output
      // node on the actual stack.
      invocation.then(gatedInvocation);
      asSequence(gatedInvocation.output)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(constructionPointer)
          .then(spawn.gated)
          .then(kb.associate(control.frameFieldPriors, kb.gated.input))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(stringIterator.create);

      val printInvocation = kb.gated.input.new Node("print");
      asSequence(printInvocation.output)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(control.arg1)
          .then(kb.print);

      val bindPrintEntrypoint = kb.entrypoint.new Node();
      // TODO: this timing is super fragile
      bindPrintEntrypoint.then(control.stackFrame.address, control.invocation);

      val bindPrint = kb.execution.new Node();
      bindPrint.conjunction(bindPrintEntrypoint, invocation);
      bindPrint.inhibit(stringIterator.advance);
      asSequence(bindPrint)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(constructionPointer, control.cxt.address, kb.suppressPosteriors(control.cxt))
          .then(kb.clearPosteriors(control.cxt))
          .then(kb.associate(control.cxt, kb.gated.input))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address)
          .then(kb.gated.gate)
          .then(
              kb.disassociate(control.cxt, kb.gated.input),
              kb.associate(control.cxt, kb.gated.output))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address)
          .then(control.invocation)
          .then(printInvocation)
          .then(kb.associate(control.frameFieldPriors, kb.gated.input))

          // In the future we might like to scope the write pointer per construction
          // frame, but that story is not fleshed out yet so let's keep it simple for now.
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(writePointer)
          .then(control.arg1)
          .then(kb.associate(control.frameFieldPriors, kb.naming))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(stringIterator.advance);

      val returnParseFrame = kb.execution.new Node();
      // shared with recognition sequence memorizer
      stringIterator.hasNextDecoder.isFalse.then(control.invocation);
      returnParseFrame.conjunction(stringIterator.hasNextDecoder.isFalse, invocation);
      asSequence(returnParseFrame)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(constructionPointer, control.returnValue.address, kb.suppressPosteriors(control.returnValue))
          .then(kb.clearPosteriors(control.returnValue))
          .then(kb.associate(control.returnValue, kb.gated.input))
          .then(control.doReturn);

      {
        val call = kb.gated.input.new Node("train: \"print(\"").output;
        recognitionSequenceMemorizer.invocation.conjunction(call, control.invocation);
        kb.data.new FinalNode<>("print(").conjunction(call, control.arg1);
        val bindBindPrint = kb.execution.new Node();
        bindBindPrint.conjunction(call, control.returnTo);
        asSequence(bindBindPrint)
            .then(control.returnValue.address)
            .thenDelay()
            .then(bindPrintEntrypoint)
            .then(kb.associate(kb.stateRecognition, kb.entrypoint));
        control.stackFrame.address.then(call);
        control.execute.activate();
        Scheduler.global.fastForwardUntilIdle();
        Scheduler.global.fastForwardFor(IntegrationProfile.PERSISTENT.period());
      }
    }
  }

  private final Parse parse;

  // Eval parses the argument and executes the resulting construct.
  private class Eval {
    final GatedBiCluster.InputCluster.Node invocation = kb.gated.input.new Node("eval");

    {
      val returnFrom = kb.gated.input.new Node("eval/returnFrom");
      asSequence(returnFrom.output)
          .then(control.doReturn);

      val executeParsed = kb.gated.input.new Node("eval/executeParsed");
      asSequence(executeParsed.output)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address, control.returnValue.address, kb.suppressPosteriors(control.stackFrame))
          .then(kb.gated.gate, kb.scalePosteriors(control.stackFrame, PUSH_FACTOR))
          .then(kb.associate(control.stackFrame, kb.gated.output))
          .then(kb.gated.gate)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(control.returnTo)
          .then(returnFrom)
          .then(kb.associate(control.frameFieldPriors, kb.gated.input))
          .then(control.execute);

      asSequence(invocation.output)
          // We're burning through *a lot* of timing nodes... It may be time to leverage a
          // dedicated profile or some other mechanism. If we go with a dedicated profile,
          // we might want to use a dedicated cluster and implement intra/intercluster
          // default profiles.
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address, kb.gated.gate, kb.suppressPosteriors(control.cxt))
          .then(spawn.gated, kb.clearPosteriors(control.cxt))
          .then(kb.associate(control.cxt, kb.gated.output))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address)
          .then(control.invocation)
          .then(parse.invocation)
          .then(kb.associate(control.frameFieldPriors, kb.entrypoint))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.tmp.address)
          .then(kb.clearPosteriors(control.tmp))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(control.arg1, control.tmp.address)
          .thenDelay()
          .then(kb.associate(control.tmp, kb.data))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address)
          .then(control.arg1, control.tmp.address)
          .thenDelay()
          .then(kb.associate(control.frameFieldPriors, kb.data))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address)
          .then(control.returnTo)
          .then(executeParsed)
          .then(kb.associate(control.frameFieldPriors, kb.gated.input))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address, control.cxt.address, kb.suppressPosteriors(control.stackFrame))
          .then(kb.scalePosteriors(control.stackFrame, PUSH_FACTOR))
          .then(kb.associate(control.stackFrame, kb.gated.output))
          .then(kb.gated.gate)
          .then(control.execute);
    }
  }

  private final Eval eval;

  private class StringLiteralBuilder {
    final Latch isParsing;

    {
      isParsing = new Latch(kb.actions, kb.input);
      val builder = new StringBuilder();

      val start = kb.execution.new Node(),
          append = kb.execution.new Node(),
          end = kb.execution.new Node();
      start.then(isParsing.set);
      end.then(isParsing.clear);

      val quote = kb.stateRecognition.new Node();
      val quoteDelayed = quote
          .then(kb.execution.new Node())
          .then(kb.execution.new Node());
      val conjunction = new ConjunctionJunction();
      stringIterator.charDecoder.forOutput('"', conjunction::add);
      conjunction.build(quote).then(recognitionClass.character);
      start.conjunction(quoteDelayed, isParsing.isFalse);
      end.conjunction(quoteDelayed, isParsing.isTrue);
      recognitionClass.character.then(control.invocation);

      // TODO: simple conjunction, once timing is more robust
      val queryIsParsing = kb.actions.new Node(isParsing);
      recognitionClass.character.getPosteriors().getEdge(queryIsParsing, IntegrationProfile.TRANSIENT).distribution
          .set(.7f);
      parse.invocation.getPosteriors().getEdge(queryIsParsing, IntegrationProfile.TRANSIENT).distribution.set(.7f);

      start.then(kb.actions.new Node(() -> builder.setLength(0)));
      end.inhibit(stringIterator.advance);
      asSequence(end)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(parse.constructionPointer, control.cxt.address, kb.suppressPosteriors(control.cxt))
          .then(kb.clearPosteriors(control.cxt))
          .then(kb.associate(control.cxt, kb.gated.input))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address)
          .then(kb.gated.gate)
          .then(
              kb.disassociate(control.cxt, kb.gated.input),
              kb.associate(control.cxt, kb.gated.output))

          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(control.tmp.address, parse.writePointer, kb.suppressPosteriors(control.tmp))
          .then(kb.clearPosteriors(control.tmp))
          .then(kb.associate(control.tmp, kb.naming))

          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.tmp.address, control.cxt.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> kb.data.new FinalNode<>(builder.toString()).activate()))
          .then(kb.associate(control.frameFieldPriors, kb.data))
          .then(stringIterator.advance);

      val notQuote = kb.stateRecognition.new Node();
      recognitionClass.character.then(notQuote).inhibitor(quote);
      append.conjunction(notQuote, isParsing.isTrue);
      append.inhibit(stringIterator.advance);
      asSequence(append)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(stringIterator.codePoint)
          .then(new CoincidentEffect.Lambda<>(kb.actions, kb.data, node -> {
            if (node.getData() instanceof Integer codePoint) {
              builder.appendCodePoint(codePoint);
            }
          }).node)
          .then(stringIterator.advance);
    }
  }

  private final StringLiteralBuilder stringLiteralBuilder;

  public LanguageBootstrap(final KnowledgeBase kb) {
    this.kb = kb;

    spawn = new Spawn();
    control = new Control();
    stringIterator = new StringIterator();
    recognitionClass = new RecognitionClass();
    recognitionSequenceMemorizer = new RecognitionSequenceMemorizer();
    parse = new Parse();
    eval = new Eval();
    stringLiteralBuilder = new StringLiteralBuilder();

    // When the input changes, we need to construct an eval call.
    asSequence(kb.inputValue.onUpdate)
        .then(control.stackFrame.address, kb.gated.gate, kb.suppressPosteriors(control.stackFrame))
        .then(spawn.gated, kb.scalePosteriors(control.stackFrame, PUSH_FACTOR))
        .then(kb.associate(control.stackFrame, kb.gated.output))
        .thenDelay(IntegrationProfile.TRANSIENT.period())
        .then(control.stackFrame.address)
        .then(control.invocation)
        .then(eval.invocation)
        .then(kb.associate(control.frameFieldPriors, kb.gated.input))
        .thenDelay(IntegrationProfile.TRANSIENT.period())
        .then(control.stackFrame.address)
        .then(control.arg1)
        .then(kb.inputValue)
        .then(kb.associate(control.frameFieldPriors, kb.data))
        .thenDelay(IntegrationProfile.TRANSIENT.period())
        .then(kb.gated.gate)
        .then(control.execute);
  }
}
