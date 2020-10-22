package ai.xng.constructs;

import java.io.Serializable;

import ai.xng.ActionNode;
import ai.xng.InputCluster;
import ai.xng.util.SerializableSupplier;
import lombok.val;

public class CharacterDecoder implements ActionNode.Action {
  private static final long serialVersionUID = 1L;

  private static interface Predicate extends Serializable {
    boolean apply(final int codePoint);
  }

  private static record Test(Predicate predicate, InputCluster.Node output) implements Serializable {
    private static final long serialVersionUID = 1L;
  }

  public final SerializableSupplier<Integer> data;
  public final InputCluster.Node isWhitespace,
      isJavaIdentifierStart,
      isJavaIdentifierPart,
      isDigit,
      isUpperCase,
      isLowerCase,
      isControl,
      isOther;

  private final Test[] tests;

  public CharacterDecoder(final SerializableSupplier<Integer> data, final InputCluster output) {
    this.data = data;
    tests = new Test[] {
        new Test(Character::isWhitespace, isWhitespace = output.new Node()),
        new Test(Character::isJavaIdentifierStart, isJavaIdentifierStart = output.new Node()),
        new Test(Character::isJavaIdentifierPart, isJavaIdentifierPart = output.new Node()),
        new Test(Character::isDigit, isDigit = output.new Node()),
        new Test(Character::isUpperCase, isUpperCase = output.new Node()),
        new Test(Character::isLowerCase, isLowerCase = output.new Node()),
        new Test(Character::isISOControl, isControl = output.new Node())
    };

    isOther = output.new Node();
  }

  @Override
  public void activate() {
    final int codePoint = data.get();
    boolean isClassified = false;
    for (val test : tests) {
      if (test.predicate().apply(codePoint)) {
        test.output().activate();
        isClassified = true;
      }
    }
    if (!isClassified) {
      isOther.activate();
    }
  }
}
