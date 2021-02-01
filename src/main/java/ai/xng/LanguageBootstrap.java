package ai.xng;

import java.util.Arrays;
import java.util.PrimitiveIterator;

import ai.xng.constructs.BooleanDecoder;
import ai.xng.constructs.CharacterDecoder;
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

    public Sequence<H> then(final Posterior next) {
      tail = tail.then(kb.execution.new Node());
      tail.then(next);
      return this;
    }

    public Sequence<H> thenDirect(final BiNode next) {
      tail.then(next);
      return new Sequence<>(head, next);
    }

    public Sequence<H> thenSequential(final Posterior... s) {
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

  private class InputIterator {
    /**
     * Node activated once a code point has been decoded.
     */
    final BiNode onNext;
    /**
     * Node that should be called once a longer processing operation is ready to
     * advance the iterator. It (or the timing cluster) can also be inhibited by
     * paths that are not ready to proceed.
     * <p>
     * This also needs to be called by the parser after it is finished setting up
     * the top-level stack frame.
     */
    final BiNode advance;
    final BooleanDecoder hasNextDecoder;
    final DataCluster.MutableNode<Integer> codePoint;
    final CharacterDecoder charDecoder;
    final InputCluster charCluster;

    {
      val iterator = kb.data.new MutableNode<PrimitiveIterator.OfInt>();
      val getIterator = kb.execution.new Node();
      getIterator.then(kb.actions.new Node(() -> iterator.setData(
          kb.inputValue.getData().codePoints().iterator())));
      kb.inputValue.onUpdate.then(getIterator);

      hasNextDecoder = new BooleanDecoder(() -> iterator.getData().hasNext(), kb.input);
      advance = kb.execution.new Node();
      advance.then(kb.actions.new Node(hasNextDecoder));

      codePoint = kb.data.new MutableNode<>();
      charCluster = new InputCluster();
      charDecoder = new CharacterDecoder(codePoint::getData, charCluster);
      onNext = kb.execution.new Node();

      asSequence(hasNextDecoder.isTrue)
          .thenSequential(
              kb.actions.new Node(() -> codePoint.setData(iterator.getData().next())),
              kb.actions.new Node(charDecoder),
              onNext)
          // Advance by default unless inhibited.
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .thenDirect(advance);
    }
  }

  private final InputIterator inputIterator;

  public final DataCluster.MutableNode<Object> literal;

  private class RecognitionClass {
    final BiCluster.Node character = kb.stateRecognition.new Node();

    {
      // character recognition capture
      // We expect recognized characters to trigger a recognition tag two nodes deep,
      // with the first being the capture itself.
      val captureDispatch = inputIterator.onNext
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
              Arrays.asList(new Cluster.PriorClusterProfile(inputIterator.charCluster, IntegrationProfile.TRANSIENT)),
              kb.stateRecognition)));
    }
  }

  private final RecognitionClass recognitionClass;

  private class StringLiteralBuilder {
    final Latch isParsing;

    {
      isParsing = new Latch(kb.actions, kb.input);

      val start = kb.execution.new Node(), append = kb.execution.new Node(), end = kb.execution.new Node();
      start.then(isParsing.set);
      end.then(isParsing.clear);

      val quote = kb.stateRecognition.new Node();
      val conjunction = new ConjunctionJunction();
      inputIterator.charDecoder.forOutput('"', conjunction::add);
      conjunction.build(quote).then(recognitionClass.character);
      start.conjunction(quote, isParsing.isFalse);
      end.conjunction(quote, isParsing.isTrue);
      recognitionClass.character.then(kb.actions.new Node(isParsing));

      start.then(kb.actions.new Node(() -> literal.setData(new StringBuilder())));
      end.then(kb.actions.new Node(() -> literal.setData(((StringBuilder) literal.getData()).toString())));

      val notQuote = kb.stateRecognition.new Node() {
        @Override
        public String toString() {
          return "notQuote";
        }
      };
      recognitionClass.character.then(notQuote).inhibitor(quote);
      append.conjunction(notQuote, isParsing.isTrue);
      append.then(kb.actions.new Node(() -> ((StringBuilder) literal.getData())
          .appendCodePoint(inputIterator.codePoint.getData())));
    }
  }

  private final StringLiteralBuilder stringLiteralBuilder;

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
    final Latch active = new Latch(kb.actions, kb.input);

    {
      // Hook sequence capture up after character capture to avoid dealing with the
      // input conjunction directly. Furthermore, some character types may change the
      // latch state.
      recognitionClass.character.then(kb.actions.new Node(active));
      asSequence(active.isTrue)
          .thenSequential(
              kb.actions.new Node(() -> {
                val posterior = kb.sequenceRecognition.new Node();
                posterior.activate();
              }),
              kb.actions.new Node(() -> Cluster.associate(
                  new Cluster.PriorClusterProfile.ListBuilder()
                      .add(kb.sequenceRecognition, IntegrationProfile.TWOGRAM)
                      .add(kb.stateRecognition).build(),
                  kb.sequenceRecognition)));
    }
  }

  public final RecognitionSequenceMemorizer recognitionSequenceMemorizer;

  private static final float STACK_FACTOR = .7f;

  private class Parser {
    // Stack-like pointer to the context node for the stack frame currently being
    // constructed.
    final StmCluster constructionStack = new StmCluster();
    final BiCluster.Node entrypoint = kb.stateRecognition.new Node();

    {
      // One of the first things we should do when we begin parsing something is start
      // a stack frame. For the root frame, we can use a recognition node, forgoing
      // the context cluster. We need to bind this to the construction stack.
      // Eventually we'll also want to handle the case where the construction stack is
      // nonempty. In preparation for that, let's do a push operation.
      asSequence(kb.inputValue.onUpdate)
          .thenSequential(
              constructionStack.address,
              kb.actions.new Node(() -> {
                Cluster.scalePosteriors(constructionStack, STACK_FACTOR);
              }))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .thenSequential(
              kb.actions.new Node(() -> kb.stateRecognition.new Node().activate()),
              kb.actions.new Node(() -> Cluster.associate(constructionStack, kb.stateRecognition)))
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .then(inputIterator.advance);

      // "print" entrypoint binding. To bind, we need to activate the stack frame
      // context and the entrypoint field identifier, and capture the conjunction with
      // the "print" entrypoint node.
      val printEntrypoint = kb.entrypoints.input.new Node();
      val bindPrintEntrypoint = kb.entrypoints.input.new Node();

      bindPrintEntrypoint.output.inhibit(inputIterator.advance);
      asSequence(bindPrintEntrypoint.output)
          // Wait for transient recognition activation to clear so we can bind to
          // entrypoint.
          .thenDelay(IntegrationProfile.TRANSIENT.period())
          .thenSequential(constructionStack.address, entrypoint)
          .thenParallel(printEntrypoint, kb.entrypoints.inhibitor)
          .thenSequential(
              kb.actions.new Node(() -> {
                Cluster.associate(
                    new Cluster.PriorClusterProfile.ListBuilder()
                        .add(kb.context.output)
                        .add(kb.stateRecognition)
                        .build(),
                    kb.context.input);
                System.out.println("ok!");
              }),
              inputIterator.advance);

      recognitionSequenceMemorizer.active.set.activate();
      kb.inputValue.setData("print");
      Scheduler.global.fastForwardUntilIdle();
      asSequence(kb.execution.new Node())
          .thenParallel(bindPrintEntrypoint, kb.entrypoints.inhibitor)
          .then(kb.actions.new Node(() -> Cluster.associate(
              Arrays.asList(new Cluster.PriorClusterProfile(kb.sequenceRecognition, IntegrationProfile.TRANSIENT)),
              kb.entrypoints.input)))
          .then(recognitionSequenceMemorizer.active.clear).head.activate();
      Scheduler.global.fastForwardUntilIdle();
    }
  }

  public LanguageBootstrap(final KnowledgeBase kb) {
    this.kb = kb;

    inputIterator = new InputIterator();
    literal = kb.data.new MutableNode<>();
    recognitionClass = new RecognitionClass();
    stringLiteralBuilder = new StringLiteralBuilder();
    recognitionSequenceMemorizer = new RecognitionSequenceMemorizer();
    new Parser();
  }
}
