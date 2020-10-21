package ai.xng;

import java.util.PrimitiveIterator;

import ai.xng.decoders.BinaryDecoder;
import ai.xng.decoders.BooleanDecoder;
import ai.xng.decoders.CharacterDecoder;
import lombok.val;

public class LanguageBootstrap {
  private final KnowledgeBase kb;

  private static record Sequence(Posterior head, Prior tail) {
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

  private class InputIterator {
    final BiNode hasNext, next;
    final BooleanDecoder hasNextDecoder;
    final DataCluster.MutableNode<Integer> codePoint;
    final BinaryDecoder rawDecoder;
    final CharacterDecoder charDecoder;

    {
      val iterator = kb.data.new MutableNode<PrimitiveIterator.OfInt>();
      val getIterator = kb.execution.new Node();
      getIterator.then(kb.actions.new Node(() -> iterator.setData(
          kb.inputValue.getData().codePoints().iterator())));
      kb.inputUpdated.then(getIterator);

      hasNextDecoder = new BooleanDecoder(() -> iterator.getData().hasNext(), kb.input);
      hasNext = kb.execution.new Node();
      getIterator
          .then(hasNext)
          .then(kb.actions.new Node(hasNextDecoder::activate));

      codePoint = kb.data.new MutableNode<>();
      next = kb.execution.new Node();
      hasNextDecoder.isTrue
          .then(next)
          .then(kb.actions.new Node(() -> codePoint.setData(iterator.getData().next())));

      val decode = kb.execution.new Node();
      next.then(decode);
      rawDecoder = new BinaryDecoder(codePoint::getData, kb.input);
      charDecoder = new CharacterDecoder(codePoint::getData, kb.input);
      decode.then(kb.actions.new Node(rawDecoder::activate));
      decode.then(kb.actions.new Node(charDecoder::activate));
    }
  }

  private final InputIterator inputIterator;

  private final DataCluster.MutableNode<Object> literal;
  private final StmCluster frameCapture = new StmCluster(), frameReplay = new StmCluster();
  private final DataCluster.FinalNode<StmCluster> frameCaptureCluster, frameReplayCluster;

  private class StringLiteralBuilder {
    final GatedBiCluster.OutputCluster.Node isParsing;

    {
      val setIsParsing = kb.context.input.new Node();
      isParsing = setIsParsing.output;

      val transitionToIsParsing = kb.recognition.new Node();
      new ConjunctionJunction.Pure()
          .addAll(inputIterator.rawDecoder.outputFor('"'))
          .build(transitionToIsParsing, IntegrationProfile.TRANSIENT);
      isParsing.inhibit(transitionToIsParsing);

      // need to clear frame capture register
      val captureFrame = program(kb,
          frameCapture.address,
          setIsParsing,
          kb.associateTransient,
          frameCaptureCluster,
          kb.contextInput);
      transitionToIsParsing.then(captureFrame.head());
    }
  }

  public LanguageBootstrap(final KnowledgeBase kb) {
    this.kb = kb;
    inputIterator = new InputIterator();
    literal = kb.data.new MutableNode<>();

    frameCaptureCluster = kb.data.new FinalNode<>(frameCapture);
    frameReplayCluster = kb.data.new FinalNode<>(frameReplay);
  }
}
