package ai.xng;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import lombok.Getter;
import lombok.val;
import lombok.experimental.Accessors;

/**
 * A utility that supports deterministically rewriting node-space n-grams.
 * <p>
 * Limitations:
 * <ul>
 * <li>Only deterministic replacement is supported.
 * <li>Limited lookahead and backtracking.
 * </ul>
 */
public class DeterministicNGramRewriter implements Serializable {
  private static final long serialVersionUID = -3557530679569047568L;

  private final List<SymbolPair> symbolPairs;
  @Getter
  private int position;
  @Getter
  private int dirtyPosition;

  private boolean isDirty() {
    return dirtyPosition >= 0;
  }

  private List<ImmutableList<SymbolPair>> history = new ArrayList<>();

  public DeterministicNGramRewriter(final Stream<SymbolPair> symbolPairs) {
    this.symbolPairs = symbolPairs.collect(Collectors.toCollection(ArrayList::new));
    values = Lists.transform(this.symbolPairs, SymbolPair::value);
  }

  @Getter
  @Accessors(fluent = true)
  private final List<Node> values;

  public static record SymbolPair(Node symbol, Node value) implements Serializable {
    private static final long serialVersionUID = -7982501031086198580L;

    @Override
    public String toString() {
      return String.format("SymbolPair(%s, %s)", symbol, value);
    }
  }

  /**
   * Returns a window spanning the specified range. May reposition the window if a
   * rewrite had dirtied any covered symbol except the last.
   */
  public List<SymbolPair> window(final int behind, final int ahead) {
    Preconditions.checkArgument(behind >= 0, "behind must be nonnegative.");
    Preconditions.checkArgument(ahead >= 0, "ahead must be nonnegative.");

    if (isDirty()) {
      position = Math.max(0, Math.min(position, dirtyPosition - ahead));
      dirtyPosition = -1;
    }

    return symbolPairs.subList(Math.max(position - behind, 0), Math.min(position + ahead, symbolPairs.size()));
  }

  /**
   * If no rewrite has occurred since the last call, advances the window if not
   * already at the end, and returns whether the window moved. If a rewrite had
   * occurred since the last call, returns true without moving the window.
   * 
   * @return
   */
  public boolean advance() {
    if (isDirty()) {
      return true;
    }

    if (position < symbolPairs.size()) {
      ++position;
      return true;
    } else {
      return false;
    }
  }

  /**
   * Rewrites a length before the current position with a replacement sequence.
   * Afterwards, the replacement is marked dirty. Subsequent advances will no-op
   * until a subsequent window repositions to capture the updates. No further
   * rewrites may be performed until the window is updated.
   */
  public void rewrite(final int length, final ImmutableList<SymbolPair> replacement) {

    if (isDirty()) {
      throw new IllegalStateException(
          "Multiple rewrites proposed in a single frame. Window must be refreshed before further rewrites.");
    }

    final int newDirty = position - length;
    if (newDirty < 0) {
      throw new IllegalArgumentException(String.format("Cannot rewrite length %s from position %s.", length, position));
    }

    history.add(ImmutableList.copyOf(symbolPairs));

    val tail = window(length, 0);
    tail.clear();
    tail.addAll(replacement);

    dirtyPosition = newDirty;
  }

  public Node single() {
    if (symbolPairs.size() != 1) {
      throw new IllegalStateException(String.format("size != 1: %s", debug()));
    }
    return symbolPairs.get(0).value;
  }

  @Override
  public String toString() {
    return symbolPairs.toString();
  }

  public String debug() {
    return String.format("%s\n    History: %s", symbolPairs,
        Lists.reverse(history).stream().map(Object::toString).collect(Collectors.joining("\n")));
  }
}