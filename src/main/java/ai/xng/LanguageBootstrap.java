package ai.xng;

import static ai.xng.KnowledgeBase.POP_FACTOR;
import static ai.xng.KnowledgeBase.PUSH_FACTOR;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Supplier;

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

    public Sequence<H> thenDirect(final BiNode next, final IntegrationProfile profile) {
      tail.then(next, profile);
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

    public Sequence<H> stanza() {
      return then(control.resetStanza)
          .thenDelay()
          .thenDelay();
    }
  }

  private class Spawn {
    // TODO: manage eviction
    final Collection<Runnable> cleanup = new ArrayList<>();

    ActionCluster.Node spawner(final Supplier<Posterior> factory) {
      return kb.actions.new Node(() -> {
        val node = factory.get();
        val trigger = node.trigger();
        cleanup.add(() -> {
          trigger.clear();
          node.getTrace().evict(Scheduler.global.now());
        });
      });
    }

    final ActionCluster.Node stateRecognition = spawner(() -> kb.stateRecognition.new Node()),
        context = spawner(() -> kb.context.new Node()),
        data = spawner(() -> kb.data.new MutableNode<>()),
        // Sequence recognition is a bit different in that we don't want to clear the
        // trace when we do stanza resets.
        sequenceRecognition = kb.actions.new Node(
            () -> cleanup.add(kb.sequenceRecognition.new Node().trigger()::clear));

    final ActionCluster.Node clearSpawns = kb.actions.new Node(() -> {
      cleanup.forEach(Runnable::run);
      cleanup.clear();
    });
  }

  private final Spawn spawn;

  private class Control {
    final ImmutableList<Cluster.PriorClusterProfile> frameFieldPriors = new Cluster.PriorClusterProfile.ListBuilder()
        .add(kb.context)
        .add(kb.naming)
        .build();

    final StmCluster stackFrame = new StmCluster("stackFrame"),
        returnValue = new StmCluster("returnValue"),
        // A register for holding an object during qualified resolution.
        cxt = new StmCluster("cxt"),
        // An intermediate register for transfer operations.
        tmp = new StmCluster("tmp");

    // The following are fields on stack frame contexts.

    // statically scoped data, including the subroutine entrypoint and any static
    // variables. The context node here is a convenient way to refer to a particular
    // subroutine being called without invoking its entrypoint. Otherwise, it would
    // not be a simple matter to suppress invocation of the entrypoint and still use
    // it as a prior condition for other logic.
    final BiCluster.Node staticContext = kb.naming.new Node("staticContext"),
        entrypoint = kb.naming.new Node("entrypoint"),
        arg1 = kb.naming.new Node("arg1"),
        // the entrypoint node to be invoked after restoring the calling context to the
        // call stack
        returnTo = kb.naming.new Node("returnTo");
    final BiCluster.Node execute = kb.entrypoint.new Node("execute"),
        doReturn = kb.entrypoint.new Node("doReturn");

    // Stanza reset needs to be in a dedicated cluster since it tends to come after
    // captures and yet should usually not itself be the target of a capture.
    //
    // Capture operations tend to be punctuated by stanza resets. Stanza resets also
    // tend to occur after other invocation-like sequences.
    final BiCluster controlCluster = new BiCluster();
    final BiCluster.Node resetStanza = controlCluster.new Node();

    void setUp() {
      resetStanza.then(
          kb.resetOutboundPosteriors(kb.execution),
          kb.resetPosteriors(stackFrame),
          kb.resetPosteriors(returnValue),
          kb.resetPosteriors(cxt),
          kb.resetPosteriors(tmp),
          kb.resetPosteriors(kb.naming),
          kb.resetPosteriors(kb.context),
          kb.resetPosteriors(kb.entrypoint),
          kb.resetPosteriors(controlCluster),
          spawn.clearSpawns);

      asSequence(execute)
          .stanza()
          .then(stackFrame.address)
          .then(staticContext)
          .then(entrypoint);

      asSequence(doReturn)
          .stanza()
          .then(stackFrame.address)
          .then(kb.scalePosteriors(stackFrame, POP_FACTOR), returnTo, kb.disassociate(stackFrame, kb.context));
    }
  }

  private final Control control;

  private class StringIterator {
    final BiNode create = kb.execution.new Node();
    /**
     * Node activated once a code point has been decoded.
     */
    final BiNode onNext = kb.execution.new Node();
    /**
     * Node that should be called once a longer processing operation is ready to
     * advance the iterator. It can also be inhibited by paths that are not ready to
     * proceed.
     */
    final BiNode advance = kb.entrypoint.new Node();
    final BooleanDecoder hasNextDecoder = new BooleanDecoder(kb.actions, kb.data, kb.input,
        data -> data instanceof Iterator<?> i ? Optional.of(i.hasNext()) : Optional.empty());
    final BiNode codePoint = kb.naming.new Node();
    final InputCluster charCluster = new InputCluster();
    final CharacterDecoder charDecoder = new CharacterDecoder(kb.actions, kb.data, charCluster);

    void setUp() {
      val iterator = kb.naming.new Node();

      val iterator_in = new CoincidentEffect.Curry<>(kb.actions, kb.data);
      val iterator_out = new CoincidentEffect.Curry<>(kb.actions, kb.data);

      asSequence(create)
          .stanza()
          .then(control.stackFrame.address)
          .then(iterator, spawn.data)
          .then(kb.capture(control.frameFieldPriors, kb.data))

          .stanza()
          .then(control.stackFrame.address)
          .then(control.arg1)
          .then(iterator_in.node)

          .stanza()
          .then(control.stackFrame.address)
          .then(iterator)
          .then(iterator_out.node)
          .then(kb.actions.new Node(() -> ((DataCluster.MutableNode<Object>) iterator_out.require())
              .setData((((String) iterator_in.require().getData()).codePoints().iterator()))))

          .stanza()
          .then(control.stackFrame.address)
          .then(codePoint, spawn.data)
          .then(kb.capture(control.frameFieldPriors, kb.data))

          .then(advance);

      asSequence(advance)
          .stanza()
          .then(control.stackFrame.address)
          .then(iterator)
          .then(hasNextDecoder.node);

      val next_in = new CoincidentEffect.Curry<>(kb.actions, kb.data);
      val next_out = new CoincidentEffect.Curry<>(kb.actions, kb.data);

      asSequence(hasNextDecoder.isTrue)
          .stanza()
          .then(control.stackFrame.address)
          .then(iterator)
          .then(next_in.node)

          .stanza()
          .then(control.stackFrame.address)
          .then(codePoint)
          .then(next_out.node)
          .then(kb.actions.new Node(() -> ((DataCluster.MutableNode<Integer>) next_out.require()).setData(
              ((Iterator<Integer>) next_in.require().getData()).next())))

          .stanza()
          .then(control.stackFrame.address)
          .then(codePoint)
          .then(charDecoder.node, onNext)
          // TODO: We might consider binding codePoint to a register first to avoid
          // polluted state during recognition.
          // Advance by default unless inhibited.
          .thenDirect(advance, IntegrationProfile.PERSISTENT);
    }
  }

  private final StringIterator stringIterator;

  private class RecognitionClass {
    final BiCluster.Node character = new BiCluster().new Node();

    void setUp() {
      // character recognition capture
      // We expect recognized characters to trigger a recognition tag two nodes deep,
      // with the first being the capture itself.
      val captureDispatch = stringIterator.onNext
          .then(kb.execution.new Node())
          .then(kb.execution.new Node()) // class recognition would trigger here
          .then(kb.execution.new Node());
      captureDispatch.inhibitor(character);
      captureDispatch.then(kb.actions.new Node(() -> {
        val capture = kb.stateRecognition.new Node();
        capture.then(character);
        capture.trigger();
      }));
      captureDispatch
          .then(kb.execution.new Node())
          .then(kb.capture(stringIterator.charCluster, kb.stateRecognition));
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
    final BiNode staticContext = kb.context.new Node("rsm"),
        entrypoint = kb.entrypoint.new Node("rsm/entrypoint");

    void setUp() {
      entrypoint.conjunction(staticContext, control.entrypoint);
      asSequence(entrypoint)
          // TODO: It may be more idiomatic to create the capture later.
          .then(control.returnValue.address, kb.suppressPosteriors(control.returnValue), spawn.stateRecognition)
          .then(kb.capture(control.returnValue, kb.stateRecognition))
          .then(stringIterator.create);

      // This is shared with String LiteralBuilder
      recognitionClass.character.then(control.stackFrame.address, control.staticContext);

      // Hook sequence capture up after character capture to avoid dealing with the
      // input conjunction directly.
      val capture = kb.execution.new Node();
      capture.conjunction(recognitionClass.character, staticContext);
      asSequence(capture)
          .then(spawn.sequenceRecognition)
          .then(kb.capture()
              .priors(kb.sequenceRecognition, IntegrationProfile.TWOGRAM)
              .priors(kb.stateRecognition)
              .posteriors(kb.sequenceRecognition));

      val captureReturn = kb.execution.new Node();
      stringIterator.hasNextDecoder.isFalse.then(control.staticContext);
      captureReturn.conjunction(stringIterator.hasNextDecoder.isFalse, staticContext);
      asSequence(captureReturn)
          .stanza()
          .then(control.returnValue.address, kb.capture()
              .baseProfiles(IntegrationProfile.TWOGRAM)
              .priors(kb.sequenceRecognition)
              .posteriors(kb.stateRecognition))
          .then(control.doReturn);
    }
  }

  public final RecognitionSequenceMemorizer recognitionSequenceMemorizer;

  private class Parse {
    final BiNode staticContext = kb.context.new Node("parse"),
        entrypoint = kb.entrypoint.new Node("parse/entrypoint");

    final BiCluster.Node constructionPointer = kb.naming.new Node("constructionPointer"),
        writePointer = kb.naming.new Node("writePointer");

    void setUp() {
      entrypoint.conjunction(staticContext, control.entrypoint);
      // One of the first things we should do when we begin parsing something is start
      // constructing a stack frame.
      asSequence(entrypoint)
          .stanza()
          .then(control.stackFrame.address)
          .then(constructionPointer, spawn.context)
          .then(kb.capture(control.frameFieldPriors, kb.context))
          .then(stringIterator.create);

      val print = kb.context.new Node("print");
      val printEntrypoint = kb.entrypoint.new Node("print/entrypoint");
      printEntrypoint.conjunction(print, control.entrypoint);
      asSequence(printEntrypoint)
          .stanza()
          .then(control.stackFrame.address)
          .then(control.arg1)
          .then(kb.print)
          .then(control.doReturn);

      val bindPrintEntrypoint = kb.entrypoint.new Node();
      val bindPrint = kb.execution.new Node();
      bindPrint.conjunction(bindPrintEntrypoint, staticContext);
      bindPrint.inhibit(stringIterator.advance, IntegrationProfile.PERSISTENT);
      asSequence(bindPrint)
          .stanza()
          .then(control.stackFrame.address)
          .then(constructionPointer, control.cxt.address, kb.suppressPosteriors(control.cxt))
          .then(kb.capture(control.cxt, kb.context))

          .stanza()
          .then(control.cxt.address)
          .then(control.staticContext)
          .then(print, kb.capture(control.frameFieldPriors, kb.context))

          // In the future we might like to scope the write pointer per construction
          // frame, but that story is not fleshed out yet so let's keep it simple for now.
          .stanza()
          .then(control.stackFrame.address)
          .then(writePointer)
          .then(control.arg1, kb.capture(control.frameFieldPriors, kb.naming))
          .then(stringIterator.advance);

      val returnParseFrame = kb.execution.new Node();
      // shared with recognition sequence memorizer
      stringIterator.hasNextDecoder.isFalse.then(control.staticContext);
      returnParseFrame.conjunction(stringIterator.hasNextDecoder.isFalse, staticContext);
      asSequence(returnParseFrame)
          .stanza()
          .then(control.stackFrame.address)
          .then(constructionPointer, control.returnValue.address, kb.suppressPosteriors(control.returnValue))
          .then(kb.capture(control.returnValue, kb.context))
          .then(control.doReturn);

      {
        val call = kb.context.new Node("train: \"print(\"");
        recognitionSequenceMemorizer.staticContext.conjunction(call, control.staticContext);
        kb.data.new FinalNode<>("print(").conjunction(call, control.arg1);
        val bindBindPrint = kb.execution.new Node();
        bindBindPrint.conjunction(call, control.returnTo);
        asSequence(bindBindPrint)
            .then(control.returnValue.address)
            .thenDelay()
            .then(bindPrintEntrypoint, kb.capture(kb.stateRecognition, kb.entrypoint));
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
    final BiNode staticContext = kb.context.new Node("eval"),
        entrypoint = kb.entrypoint.new Node("eval/entrypoint");

    void setUp() {
      entrypoint.conjunction(staticContext, control.entrypoint);

      val executeParsed = kb.entrypoint.new Node("eval/executeParsed");
      asSequence(executeParsed)
          .stanza()
          .then(control.stackFrame.address, control.returnValue.address, kb.suppressPosteriors(control.stackFrame))
          .then(kb.scalePosteriors(control.stackFrame, PUSH_FACTOR))
          .then(kb.capture(control.stackFrame, kb.context))

          .stanza()
          .then(control.stackFrame.address)
          .then(control.returnTo)
          // Note that at some point, we have considered expanding suppressPosteriors to
          // also suppress the trace, for use in pulling down nodes after activation to
          // clear working memory. If we do end up needing that behavior, we will need to
          // reconcile against this use case where we suppress posteriors in order to
          // highlight a node's trace without side effects.
          .then(control.doReturn, kb.suppressPosteriors(kb.entrypoint),
              kb.capture(control.frameFieldPriors, kb.entrypoint))
          .thenDelay()
          .then(control.execute);

      asSequence(entrypoint)
          .stanza()
          .then(control.cxt.address, kb.suppressPosteriors(control.cxt), spawn.context)
          .then(kb.capture(control.cxt, kb.context))

          .stanza()
          .then(control.cxt.address)
          .then(control.staticContext)
          .then(parse.staticContext, kb.capture(control.frameFieldPriors, kb.context))

          .stanza()
          .then(control.stackFrame.address)
          .then(control.arg1, control.tmp.address, kb.suppressPosteriors(control.tmp))
          .then(kb.capture(control.tmp, kb.data))

          .stanza()
          .then(control.cxt.address)
          .then(control.arg1, control.tmp.address)
          .then(kb.capture(control.frameFieldPriors, kb.data))

          .stanza()
          .then(control.cxt.address)
          .then(control.returnTo)
          .then(executeParsed, kb.suppressPosteriors(kb.entrypoint),
              kb.capture(control.frameFieldPriors, kb.entrypoint))

          .stanza()
          .then(control.stackFrame.address, control.cxt.address, kb.suppressPosteriors(control.stackFrame))
          .then(kb.scalePosteriors(control.stackFrame, PUSH_FACTOR))
          .then(kb.capture(control.stackFrame, kb.context))

          .then(control.execute);
    }
  }

  private final Eval eval;

  private class StringLiteralBuilder {
    final Latch isParsing = new Latch(kb.actions, kb.input);

    void setUp() {
      val builder = new StringBuilder();

      val start = kb.execution.new Node();
      val append = kb.execution.new Node();
      val end = kb.execution.new Node();
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
      // Shared with RSM
      recognitionClass.character.then(control.stackFrame.address, control.staticContext);

      kb.actions.new Node(isParsing).conjunction(recognitionClass.character, parse.staticContext);

      start.then(kb.actions.new Node(() -> builder.setLength(0)));
      end.inhibit(stringIterator.advance, IntegrationProfile.PERSISTENT);
      asSequence(end)
          .stanza()
          .then(control.stackFrame.address)
          .then(parse.constructionPointer, control.cxt.address, kb.suppressPosteriors(control.cxt))
          .then(kb.capture(control.cxt, kb.context))

          .stanza()
          .then(control.stackFrame.address)
          .then(control.tmp.address, parse.writePointer, kb.suppressPosteriors(control.tmp))
          .then(kb.capture(control.tmp, kb.naming))

          .stanza()
          .then(control.tmp.address, control.cxt.address)
          .then(kb.actions.new Node(() -> kb.data.new FinalNode<>(builder.toString()).trigger()))
          .then(kb.capture(control.frameFieldPriors, kb.data))
          .then(stringIterator.advance);

      val notQuote = kb.stateRecognition.new Node();
      recognitionClass.character.then(notQuote).inhibitor(quote);
      append.conjunction(notQuote, isParsing.isTrue);
      append.inhibit(stringIterator.advance, IntegrationProfile.PERSISTENT);
      asSequence(append)
          .stanza()
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

    control.setUp();
    stringIterator.setUp();
    recognitionClass.setUp();
    recognitionSequenceMemorizer.setUp();
    parse.setUp();
    eval.setUp();
    stringLiteralBuilder.setUp();

    // When the input changes, we need to construct an eval call.
    asSequence(kb.inputValue.onUpdate)
        .then(control.stackFrame.address, kb.suppressPosteriors(control.stackFrame), spawn.context)
        .then(kb.scalePosteriors(control.stackFrame, PUSH_FACTOR))
        .then(kb.capture(control.stackFrame, kb.context))

        .stanza()
        .then(control.stackFrame.address)
        .then(control.staticContext)
        .then(eval.staticContext, kb.capture(control.frameFieldPriors, kb.context))

        .stanza()
        .then(control.stackFrame.address)
        .then(control.arg1)
        .then(kb.inputValue, kb.capture(control.frameFieldPriors, kb.data))
        .then(control.execute);
  }
}
