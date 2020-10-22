package ai.xng;

import java.util.PrimitiveIterator;

import ai.xng.constructs.BinaryDecoder;
import ai.xng.constructs.BooleanDecoder;
import ai.xng.constructs.CharacterDecoder;
import ai.xng.constructs.Latch;
import lombok.val;

public class LanguageBootstrap {
  private final KnowledgeBase kb;

  public static record Sequence(Posterior head, Prior tail) {
  }

  private static Sequence program(final KnowledgeBase kb, final Posterior... sequence) {
    final BiCluster.Node head = kb.execution.new Node();
    BiCluster.Node tail = head;
    tail.then(sequence[0]);
    for (int i = 1; i < sequence.length; ++i) {
      tail = tail.then(kb.execution.new Node());
      tail.then(sequence[i]);
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
    final long dt = IntegrationProfile.TRANSIENT.defaultDelay();
    for (long t = dt; t < period; t += dt) {
      tail = tail.then(kb.timing.new Node());
    }
    return new Sequence(head, tail);
  }

  private class InputIterator {
    final BiNode onNext;
    final BooleanDecoder hasNextDecoder;
    final DataCluster.MutableNode<Integer> codePoint;
    final BinaryDecoder rawDecoder;
    final CharacterDecoder charDecoder;

    {
      val iterator = kb.data.new MutableNode<PrimitiveIterator.OfInt>();
      val getIterator = kb.execution.new Node();
      getIterator.then(kb.actions.new Node(() -> iterator.setData(
          kb.inputValue.getData().codePoints().iterator())));
      kb.inputValue.onUpdate.then(getIterator);

      hasNextDecoder = new BooleanDecoder(() -> iterator.getData().hasNext(), kb.input);
      val hasNext = kb.execution.new Node();
      getIterator
          .then(hasNext)
          .then(kb.actions.new Node(hasNextDecoder));

      codePoint = kb.data.new MutableNode<>();
      val next = kb.execution.new Node();
      hasNextDecoder.isTrue
          .then(next)
          .then(kb.actions.new Node(() -> codePoint.setData(iterator.getData().next())));

      val decode = kb.execution.new Node();
      next.then(decode);
      rawDecoder = new BinaryDecoder(codePoint::getData, kb.input);
      charDecoder = new CharacterDecoder(codePoint::getData, kb.input);
      decode.then(kb.actions.new Node(rawDecoder));
      decode.then(kb.actions.new Node(charDecoder));
      onNext = kb.execution.new Node();
      decode.then(onNext);

      val delay = timingChain(IntegrationProfile.TRANSIENT.period());
      onNext.then(delay.head);
      delay.tail.then(hasNext);
    }
  }

  private final InputIterator inputIterator;

  public final DataCluster.MutableNode<Object> literal;

  private class RecognitionClass {
    final BiCluster.Node character = kb.recognition.new Node();
  }

  private final RecognitionClass recognitionClass;

  private class StringLiteralBuilder {
    final Latch isParsing;

    {
      isParsing = new Latch(kb.actions, kb.input);

      val start = kb.execution.new Node(), append = kb.execution.new Node(), end = kb.execution.new Node();
      start.then(isParsing.set);
      end.then(isParsing.clear);

      val quoteInput = inputIterator.rawDecoder.outputFor('"');
      val quote = kb.recognition.new Node();
      quote.then(recognitionClass.character);
      new ConjunctionJunction.Pure()
          .addAll(quoteInput)
          .build(quote);
      start.conjunction(quote, isParsing.isFalse);
      end.conjunction(quote, isParsing.isTrue);
      inputIterator.onNext.then(kb.actions.new Node(isParsing));

      start.then(kb.actions.new Node(() -> literal.setData(new StringBuilder())));
      end.then(kb.actions.new Node(() -> literal.setData(((StringBuilder) literal.getData()).toString())));

      val notQuote = kb.recognition.new Node();
      notQuote.disjunction(quoteInput.complement());
      append.conjunction(notQuote, isParsing.isTrue);
      append.then(kb.actions.new Node(() -> ((StringBuilder) literal.getData())
          .appendCodePoint(inputIterator.codePoint.getData())));
    }
  }

  private final StringLiteralBuilder stringLiteralBuilder;

  public LanguageBootstrap(final KnowledgeBase kb) {
    this.kb = kb;
    inputIterator = new InputIterator();
    literal = kb.data.new MutableNode<>();
    recognitionClass = new RecognitionClass();
    stringLiteralBuilder = new StringLiteralBuilder();
  }
}
