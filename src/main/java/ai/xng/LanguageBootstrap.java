package ai.xng;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import ai.xng.constructs.BooleanDecoder;
import ai.xng.constructs.CharacterDecoder;
import ai.xng.constructs.Decoder;
import ai.xng.constructs.Latch;
import lombok.AllArgsConstructor;
import lombok.val;

public class LanguageBootstrap {
  public static final float STACK_FACTOR = .5f;

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

    public Sequence<H> then(final Posterior... s) {
      for (val e : s) {
        tail = tail.then(kb.execution.new Node());
        if (e != null) {
          tail.then(e);
        }
      }
      return this;
    }

    public Sequence<H> thenParallel(final Posterior... p) {
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

    final StmCluster stackFrame = new StmCluster(),
        returnValue = new StmCluster(),
        cxt = new StmCluster(),
        tmp = new StmCluster();
    final BiCluster.Node invocation = kb.naming.new Node(),
        arg1 = kb.naming.new Node(),
        returnTo = kb.naming.new Node();
    final BiCluster.Node execute = kb.entrypoint.new Node(),
        doReturn = kb.entrypoint.new Node();

    {
      // TODO: consider inheriting more from static frames and creating an instance
      // differentiator here so that we can allocate new data onto static frames (or
      // leaving that as a caller responsibility).
      asSequence(execute)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              stackFrame.address,
              invocation,
              kb.gated.gate);

      asSequence(doReturn)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(stackFrame.address)
          .thenParallel(
              returnTo,
              kb.actions.new Node(() -> Cluster.scalePosteriors(stackFrame, 1 / STACK_FACTOR)))
          .thenParallel(
              kb.actions.new Node(() -> Cluster.disassociate(stackFrame, kb.gated.output)),
              kb.actions.new Node(() -> Cluster.disassociate(stackFrame, kb.naming)))
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

      asSequence(create)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.stackFrame.address,
              iterator,
              spawn.data,
              kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.data)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              kb.actions.new Node(() -> kb.data.rxActivations().take(2).toList()
                  .subscribe(data -> ((DataCluster.MutableNode<Object>) data.get(1)).setData(
                      (((String) data.get(0).getData()).codePoints().iterator())))),
              control.stackFrame.address,
              control.arg1)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.stackFrame.address,
              iterator)

          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(codePoint)
          .then(spawn.data)
          .then(kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.data)))

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

      asSequence(hasNextDecoder.isTrue)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(kb.actions.new Node(() -> kb.data.rxActivations().take(2).toList()
              .subscribe(data -> ((DataCluster.MutableNode<Integer>) data.get(1)).setData(
                  ((Iterator<Integer>) ((DataNode) data.get(0)).getData()).next()))))
          .then(control.stackFrame.address)
          .then(iterator)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(codePoint)
          .thenDelay()
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
          .then(kb.actions.new Node(() -> Cluster.associate(
              Arrays.asList(new Cluster.PriorClusterProfile(stringIterator.charCluster, IntegrationProfile.TRANSIENT)),
              kb.stateRecognition)));
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
      invocation = kb.entrypoint.new Node();
      val gatedInvocation = kb.gated.input.new Node();
      invocation.then(gatedInvocation);
      asSequence(gatedInvocation.output)
          // TODO: It may be more idiomatic to create the capture later, but with current
          // limitations that would not allow us to bind to STM while also binding to the
          // captured sequence.
          .then(control.returnValue.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.disassociate(control.returnValue, kb.stateRecognition)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.returnValue.address)
          .then(spawn.stateRecognition)
          .then(kb.actions.new Node(() -> Cluster.associate(control.returnValue, kb.stateRecognition)))
          .then(stringIterator.create);

      // This is shared with String LiteralBuilder
      recognitionClass.character.then(control.stackFrame.address, control.invocation);

      // Hook sequence capture up after character capture to avoid dealing with the
      // input conjunction directly. Furthermore, some character types may change the
      // latch state.
      val capture = kb.execution.new Node();
      capture.conjunction(recognitionClass.character, invocation);
      asSequence(capture)
          .then(
              spawn.sequenceRecognition,
              kb.actions.new Node(() -> Cluster.associate(
                  new Cluster.PriorClusterProfile.ListBuilder()
                      .add(kb.sequenceRecognition, IntegrationProfile.TWOGRAM)
                      .add(kb.stateRecognition).build(),
                  kb.sequenceRecognition)));

      val captureReturn = kb.execution.new Node();
      stringIterator.hasNextDecoder.isFalse.then(control.invocation);
      captureReturn.conjunction(stringIterator.hasNextDecoder.isFalse, invocation);
      asSequence(captureReturn)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.returnValue.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.associate(new Cluster.PriorClusterProfile.ListBuilder()
              .baseProfiles(IntegrationProfile.TWOGRAM)
              .add(kb.sequenceRecognition)
              .build(), kb.stateRecognition)))
          .then(control.doReturn);
    }
  }

  public final RecognitionSequenceMemorizer recognitionSequenceMemorizer;

  private class Parse {
    final BiNode invocation = kb.entrypoint.new Node();

    final BiCluster.Node constructionPointer = kb.naming.new Node(),
        writePointer = kb.naming.new Node();

    {
      val gatedInvocation = kb.gated.input.new Node();
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
          .then(
              control.stackFrame.address,
              constructionPointer,
              spawn.gated,
              kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.gated.input)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(stringIterator.create);

      val printInvocation = kb.gated.input.new Node();
      asSequence(printInvocation.output)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .thenParallel(kb.print, control.stackFrame.address)
          .then(control.arg1);

      val bindPrintEntrypoint = kb.entrypoint.new Node();
      // TODO: this timing is super fragile
      bindPrintEntrypoint.then(control.stackFrame.address, control.invocation);

      val bindPrint = kb.execution.new Node();
      bindPrint.conjunction(bindPrintEntrypoint, invocation);
      bindPrint.inhibit(stringIterator.advance);
      asSequence(bindPrint)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.disassociate(control.cxt, kb.gated.output)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .thenParallel(constructionPointer, control.cxt.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.associate(control.cxt, kb.gated.input)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.cxt.address,
              kb.gated.gate)
          .thenParallel(
              kb.actions.new Node(() -> Cluster.disassociate(control.cxt, kb.gated.input)),
              kb.actions.new Node(() -> Cluster.associate(control.cxt, kb.gated.output)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.cxt.address,
              control.invocation,
              printInvocation)
          .then(kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.gated.input)))

          // In the future we might like to scope the write pointer per construction
          // frame, but that story is not fleshed out yet so let's keep it simple for now.
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.stackFrame.address,
              writePointer,
              control.arg1,
              kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.naming)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(stringIterator.advance);

      val returnParseFrame = kb.execution.new Node();
      // shared with recognition sequence memorizer
      stringIterator.hasNextDecoder.isFalse.then(control.invocation);
      returnParseFrame.conjunction(stringIterator.hasNextDecoder.isFalse, invocation);
      asSequence(returnParseFrame)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.returnValue.address,
              null,
              kb.actions.new Node(() -> Cluster.disassociate(control.returnValue, kb.gated.input)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .thenParallel(constructionPointer, control.returnValue.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.associate(control.returnValue, kb.gated.input)))
          .then(control.doReturn);

      {
        val call = kb.gated.input.new Node().output;
        recognitionSequenceMemorizer.invocation.conjunction(call, control.invocation);
        kb.data.new FinalNode<>("print(").conjunction(call, control.arg1);
        val bindBindPrint = kb.execution.new Node();
        bindBindPrint.conjunction(call, control.returnTo);
        asSequence(bindBindPrint)
            .then(control.returnValue.address)
            .thenDelay()
            .thenParallel(
                bindPrintEntrypoint,
                kb.actions.new Node(() -> Cluster.disassociate(control.returnValue, kb.stateRecognition)))
            .then(kb.actions.new Node(() -> Cluster.associate(kb.stateRecognition, kb.entrypoint)));
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
    final GatedBiCluster.InputCluster.Node invocation = kb.gated.input.new Node();

    {
      val returnFrom = kb.gated.input.new Node();
      asSequence(returnFrom.output)
          .then(control.doReturn);

      val executeParsed = kb.gated.input.new Node();
      asSequence(executeParsed.output)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.stackFrame.address,
              kb.actions.new Node(() -> Cluster.scalePosteriors(control.stackFrame, STACK_FACTOR)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .thenParallel(control.stackFrame.address, control.returnValue.address)
          .then(kb.gated.gate)
          .then(kb.actions.new Node(() -> Cluster.associate(control.stackFrame, kb.gated.output)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.stackFrame.address,
              control.returnTo,
              returnFrom,
              kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.gated.input)),
              control.execute);

      asSequence(invocation.output)
          // We're burning through *a lot* of timing nodes... It may be time to leverage a
          // dedicated profile or some other mechanism. If we go with a dedicated profile,
          // we might want to use a dedicated cluster and implement intra/intercluster
          // default profiles.
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          // Additionally, it may be worthwhile to create a dedicated bind/push mechanism.
          .then(
              control.cxt.address,
              null,
              kb.actions.new Node(() -> Cluster.disassociate(control.cxt, kb.gated.output)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .thenParallel(control.cxt.address, kb.gated.gate)
          .then(spawn.gated)
          .then(kb.actions.new Node(() -> Cluster.associate(control.cxt, kb.gated.output)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address)
          .then(control.invocation)
          .then(parse.invocation)
          .then(kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.entrypoint)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.tmp.address,
              null,
              kb.actions.new Node(() -> Cluster.disassociate(control.tmp, kb.data)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .thenParallel(control.arg1, control.tmp.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.associate(control.tmp, kb.data)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address)
          .thenParallel(control.arg1, control.tmp.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.data)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.cxt.address,
              control.returnTo,
              executeParsed,
              kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.gated.input)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.stackFrame.address,
              kb.actions.new Node(() -> Cluster.scalePosteriors(control.stackFrame, STACK_FACTOR)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .thenParallel(control.stackFrame.address, control.cxt.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.associate(control.stackFrame, kb.gated.output)))
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
      recognitionClass.character.getPosteriors().getDistribution(queryIsParsing, IntegrationProfile.TRANSIENT).set(.7f);
      parse.invocation.getPosteriors().getDistribution(queryIsParsing, IntegrationProfile.TRANSIENT).set(.7f);

      start.then(kb.actions.new Node(() -> builder.setLength(0)));
      end.inhibit(stringIterator.advance);
      asSequence(end)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.cxt.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.disassociate(control.cxt, kb.gated.output)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .thenParallel(parse.constructionPointer, control.cxt.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.associate(control.cxt, kb.gated.input)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(
              control.cxt.address,
              kb.gated.gate)
          .thenParallel(
              kb.actions.new Node(() -> Cluster.disassociate(control.cxt, kb.gated.input)),
              kb.actions.new Node(() -> Cluster.associate(control.cxt, kb.gated.output)))

          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.tmp.address)
          .thenDelay()
          .then(kb.actions.new Node(() -> Cluster.disassociate(control.tmp, kb.naming)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .thenParallel(control.tmp.address, parse.writePointer)
          .then(kb.actions.new Node(() -> Cluster.associate(control.tmp, kb.naming)))

          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .thenParallel(control.tmp.address, control.cxt.address)
          .thenDelay()
          .then(
              kb.actions.new Node(() -> kb.data.new FinalNode<>(builder.toString()).activate()),
              kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.data)),
              stringIterator.advance);

      val notQuote = kb.stateRecognition.new Node();
      recognitionClass.character.then(notQuote).inhibitor(quote);
      append.conjunction(notQuote, isParsing.isTrue);
      append.inhibit(stringIterator.advance);
      asSequence(append)
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(control.stackFrame.address)
          .then(stringIterator.codePoint)
          .then(new Decoder(kb.actions, kb.data) {
            @Override
            protected void decode(final Object data) {
              if (data instanceof Integer codePoint) {
                builder.appendCodePoint(codePoint);
              }
            }
          }.node)
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
        .then(
            control.stackFrame.address,
            kb.actions.new Node(() -> Cluster.scalePosteriors(control.stackFrame, STACK_FACTOR)))
        .thenDelay(IntegrationProfile.TRANSIENT.period())
        .thenParallel(control.stackFrame.address, kb.gated.gate)
        .then(spawn.gated)
        .then(kb.actions.new Node(() -> Cluster.associate(control.stackFrame, kb.gated.output)))
        .thenDelay(IntegrationProfile.TRANSIENT.period())
        .then(
            control.stackFrame.address,
            control.invocation,
            eval.invocation,
            kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.gated.input)))
        .thenDelay(IntegrationProfile.TRANSIENT.period())
        .then(
            control.stackFrame.address,
            control.arg1,
            kb.inputValue,
            kb.actions.new Node(() -> Cluster.associate(control.frameFieldPriors, kb.data)))
        .thenDelay(IntegrationProfile.TRANSIENT.period())
        .then(control.execute);
  }
}
