package ai.xng;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import lombok.Getter;
import lombok.val;

/**
 * A utility that supports deterministically rewriting node-space n-grams.
 * <p>
 * Limitations:
 * <ul>
 * <li>Only deterministic replacement is supported.
 * <li>Limited lookahead and backtracking.
 * </ul>
 */
public class DeterministicNGramRewriter {
  public static record SymbolPair(Node symbol, Node value) {
    @Override
    public String toString() {
      return String.format("SymbolPair(%s, %s)", symbol, value);
    }
  }

  private final List<SymbolPair> symbolPairs;
  @Getter
  private int position;
  @Getter
  private int dirtyPosition;

  private boolean isDirty() {
    return dirtyPosition >= 0;
  }

  public static final int MAX_HISTORY = 10;
  private List<ImmutableList<SymbolPair>> history = new ArrayList<>();

  public DeterministicNGramRewriter(final Stream<SymbolPair> symbolPairs) {
    this.symbolPairs = symbolPairs.collect(Collectors.toCollection(ArrayList::new));
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
      throw new IllegalStateException(String.format(
          "Multiple rewrites proposed in a single frame. Window must be refreshed before further rewrites.\nState: %s",
          this));
    }

    final int newDirty = position - length;
    if (newDirty < 0) {
      throw new IllegalArgumentException(String.format("Cannot rewrite length %s from position %s.", length, position));
    }

    if (history.size() == MAX_HISTORY) {
      history.remove(0);
    }
    // Stringizing lazily could be misleading if nodes contain mutable state, but on
    // the other hand stringizing eagerly can be pretty expensive.
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

  private static String toString(final Iterable<SymbolPair> symbolPairs) {
    val parts = new ArrayList<String>();
    val sb = new StringBuilder();

    for (val symbolPair : symbolPairs) {
      if (symbolPair.symbol() != null && symbolPair.value() != null
          && symbolPair.symbol().getValue() == KnowledgeBase.Common.codePoint
          && symbolPair.value().getValue() instanceof Integer) {
        if (sb.length() == 0) {
          sb.append('"');
        }
        sb.appendCodePoint((int) symbolPair.value().getValue());
      } else {
        if (sb.length() > 0) {
          sb.append('"');
          parts.add(sb.toString());
          sb.setLength(0);
        }

        sb.append('(').append(symbolPair.symbol()).append(", ").append(symbolPair.value());
        if (symbolPair.value() != null && symbolPair.value().getValue() instanceof Integer
            && !Character.isISOControl((int) symbolPair.value().getValue())) {
          sb.append("/'").appendCodePoint((int) symbolPair.value().getValue()).append("'");
        }
        sb.append(')');
        parts.add(sb.toString());
        sb.setLength(0);
      }
    }
    if (sb.length() > 0) {
      sb.append('"');
      parts.add(sb.toString());
    }
    return parts.toString();
  }

  @Override
  public String toString() {
    return toString(symbolPairs);
  }

  public String debug() {
    return String.format("%s\n    History: %s", this,
        Lists.reverse(history).stream().map(DeterministicNGramRewriter::toString).collect(Collectors.joining("\n")));
  }
}