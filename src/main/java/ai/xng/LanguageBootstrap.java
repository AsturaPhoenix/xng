package ai.xng;

import java.util.Arrays;
import java.util.PrimitiveIterator;
import java.util.function.BiConsumer;

import ai.xng.constructs.BooleanDecoder;
import ai.xng.constructs.CharacterDecoder;
import ai.xng.constructs.Latch;
import lombok.val;

public class LanguageBootstrap {
  private final KnowledgeBase kb;

  public static record Sequence(Posterior head, Prior tail) {
  }

  private static Sequence program(final KnowledgeBase kb, final Posterior s0, final Posterior... sequence) {
    // At some point, we'll need to examine whether it's really worth having a
    // separate timing cluster, because it's probably not.
    final BiCluster.Node head = s0 == null ? kb.timing.new Node() : kb.execution.new Node();
    BiCluster.Node tail = head;
    if (s0 != null) {
      tail.then(s0);
    }
    for (val si : sequence) {
      if (si == null) {
        tail = tail.then(kb.timing.new Node());
      } else {
        tail = tail.then(kb.execution.new Node());
        tail.then(si);
      }
    }
    return new Sequence(head, tail);
  }

  /**
   * Produces a chain of nodes that spans roughly {@code period} between head and
   * tail activation.
   */
  public Sequence timingChain(final long period) {
    val head = kb.timing.new Node();
    BiNode tail = head;
    final long dt = IntegrationProfile.TRANSIENT.defaultInterval();
    for (long t = dt; t < period; t += dt) {
      tail = tail.then(kb.timing.new Node());
    }
    return new Sequence(head, tail);
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
      val hasNext = kb.execution.new Node();
      iterator.onUpdate
          .then(hasNext)
          .then(kb.actions.new Node(hasNextDecoder));

      codePoint = kb.data.new MutableNode<>();
      val next = kb.execution.new Node();
      hasNextDecoder.isTrue
          .then(next)
          .then(kb.actions.new Node(() -> codePoint.setData(iterator.getData().next())));

      charCluster = new InputCluster();
      charDecoder = new CharacterDecoder(codePoint::getData, charCluster);
      onNext = kb.execution.new Node();
      next.then(kb.execution.new Node())
          .then(
              kb.actions.new Node(charDecoder),
              onNext);

      advance = kb.execution.new Node();
      val delay = timingChain(IntegrationProfile.TRANSIENT.period());
      advance.then(delay.head);
      delay.tail.then(hasNext);

      // Advance by default unless inhibited.
      onNext.then(advance);
    }
  }

  private final InputIterator inputIterator;
  // Stack-like pointer to the context node for the stack frame currently being
  // constructed.
  private final StmCluster constructionStack = new StmCluster();
  public final BiCluster.Node entrypoint;
  public final DataCluster.MutableNode<Object> literal;

  private class RecognitionClass {
    final BiCluster.Node character = kb.recognition.new Node() {
      @Override
      public String toString() {
        return "character";
      }
    };

    {
      // character recognition capture
      // We expect recognized characters to trigger a recognition tag two nodes deep,
      // with the first being the capture itself.
      inputIterator.onNext
          .then(kb.timing.new Node())
          .then(kb.timing.new Node()) // recognition would trigger here
          .then(kb.actions.new Node(() -> {
            final int cp = inputIterator.codePoint.getData();
            val capture = kb.recognition.new Node() {
              @Override
              public String toString() {
                return new StringBuilder("'").appendCodePoint(cp).append('\'').toString();
              }
            };
            Cluster.associate(
                Arrays.asList(new Cluster.PriorClusterProfile(inputIterator.charCluster, IntegrationProfile.TRANSIENT)),
                capture, Scheduler.global.now(), 1);
            capture.then(character);
            capture.activate();
          }))
          .inhibitor(character);
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

      val quote = kb.recognition.new Node();
      val conjunction = new ConjunctionJunction();
      inputIterator.charDecoder.forOutput('"', conjunction::add);
      conjunction.build(quote).then(recognitionClass.character);
      start.conjunction(quote, isParsing.isFalse);
      end.conjunction(quote, isParsing.isTrue);
      inputIterator.onNext.then(kb.actions.new Node(isParsing));

      start.then(kb.actions.new Node(() -> literal.setData(new StringBuilder())));
      end.then(kb.actions.new Node(() -> literal.setData(((StringBuilder) literal.getData()).toString())));

      val notQuote = kb.recognition.new Node() {
        @Override
        public String toString() {
          return "notQuote";
        }
      };
      recognitionClass.character.then(notQuote);
      quote.inhibit(notQuote);
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
    int count = 0;
    final ActionNode captureRecognitionSequence = kb.actions.new Node(() -> {
      final int i = ++count;
      val posterior = kb.recognition.new Node() {
        @Override
        public String toString() {
          return "sqc " + i;
        }
      };
      Cluster.associate(new Cluster.PriorClusterProfile.ListBuilder()
          .baseProfiles(IntegrationProfile.TRANSIENT, IntegrationProfile.TWOGRAM)
          .addCluster(kb.recognition).build(),
          posterior, Scheduler.global.now(), 1);
      posterior.then(kb.actions.new Node(
          () -> System.out.println(new StringBuilder()
              .append(Scheduler.global.now()).append('\n')
              .append(i).append('\n')
              .append(posterior.getPriors()))));
      posterior.activate();
    });

    {
      // Hook sequence capture up after character capture to avoid dealing with the
      // input conjunction directly. Furthermore, some character types may change the
      // latch state.
      recognitionClass.character.then(kb.actions.new Node(active));
      active.isTrue.then(captureRecognitionSequence);
    }
  }

  public final RecognitionSequenceMemorizer recognitionSequenceMemorizer;

  public LanguageBootstrap(final KnowledgeBase kb) {
    this.kb = kb;

    inputIterator = new InputIterator();
    literal = kb.data.new MutableNode<>();
    recognitionClass = new RecognitionClass();
    stringLiteralBuilder = new StringLiteralBuilder();
    recognitionSequenceMemorizer = new RecognitionSequenceMemorizer();

    entrypoint = kb.recognition.new Node();

    {
      // "print" entrypoint binding. To bind, we need to activate the stack frame
      // context and the entrypoint field identifier, and capture the conjunction with
      // the "print" entity node.
      //
      // It might be nice if we can easily curry the stack frame context into a "bind"
      // routine that just takes the field identifier and value (entity node). We
      // might be able to do that by sticking with named arguments and linking the
      // contexts.
      val bindPrintEntrypoint = kb.execution.new Node();
      // Wait for transient recognition activation to clear so we can bind to
      // entrypoint.
      val delay = timingChain(IntegrationProfile.TRANSIENT.period());
      bindPrintEntrypoint.then(delay.head);
      delay.tail.then(constructionStack.address, entrypoint);
      val printEntrypointEntity = kb.context.input.new Node();
      delay.tail.then(program(kb,
          null,
          null,
          printEntrypointEntity,
          kb.actions.new Node(() -> {
            Cluster.associate(
                new Cluster.PriorClusterProfile.ListBuilder()
                    .addCluster(constructionStack)
                    .addCluster(kb.recognition)
                    .build(),
                kb.context.input);
            System.out.println("ok!");
          })).head);

      recognitionSequenceMemorizer.active.set.activate();
      kb.inputValue.setData("print");
      Scheduler.global.fastForwardUntilIdle();
      Scheduler.global.fastForwardFor(IntegrationProfile.TRANSIENT.peak());
      Cluster.associate(Arrays.asList(new Cluster.PriorClusterProfile(kb.recognition, IntegrationProfile.TRANSIENT)),
          bindPrintEntrypoint, Scheduler.global.now(), 1);
      recognitionSequenceMemorizer.active.clear.activate();
    }
  }
}
