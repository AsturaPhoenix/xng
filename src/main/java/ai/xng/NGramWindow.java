package ai.xng;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import lombok.EqualsAndHashCode;
import lombok.val;

/**
 * Represents an unmodifiable n-gram window. Supports nondeterministic
 * node-space n-gram rewrite by producing new windows.
 */
@EqualsAndHashCode
public class NGramWindow implements Serializable {
  private static final long serialVersionUID = -2439381930530133112L;

  public static record SymbolPair(Node symbol, Node value) {
    @Override
    public String toString() {
      return String.format("SymbolPair(%s, %s)", symbol, value);
    }
  }

  public static record Config(int behind, int ahead) {
    public Config {
      Preconditions.checkArgument(behind >= 0, "behind must be nonnegative.");
      Preconditions.checkArgument(ahead >= 0, "ahead must be nonnegative.");
    }
  }

  /**
   * Number of recent history sequences to include in debug stringization.
   */
  public static final int MAX_HISTORY = 10;

  public final Config config;
  private final NGramWindow parent;

  public final ImmutableList<SymbolPair> symbolPairs;
  public final int position;

  public NGramWindow(final Stream<SymbolPair> symbolPairs, final int behind, final int ahead) {
    config = new Config(behind, ahead);
    parent = null;
    this.symbolPairs = symbolPairs.collect(ImmutableList.toImmutableList());
    position = 0;
  }

  private NGramWindow(final NGramWindow parent, final ImmutableList<SymbolPair> symbolPairs, final int position,
      final Config config) {
    this.config = config;
    this.parent = parent;
    this.symbolPairs = symbolPairs;
    this.position = position;
  }

  private NGramWindow(final NGramWindow parent, final ImmutableList<SymbolPair> symbolPairs, final int position) {
    this(parent, symbolPairs, position, parent.config);
  }

  /**
   * Returns the window specified by the configured lookahead and lookbehind at
   * the current position, clamped to the sequence.
   */
  public ImmutableList<SymbolPair> window() {
    return symbolPairs.subList(Math.max(position - config.behind, 0),
        Math.min(position + config.ahead, symbolPairs.size()));
  }

  /**
   * Produces a window advanced by one symbol, or null if the end of the sequence
   * has been reached.
   */
  public NGramWindow advance() {
    return position < symbolPairs.size() ? new NGramWindow(this, symbolPairs, position + 1) : null;
  }

  /**
   * Rewrites a length before the current position with a replacement sequence,
   * and returns the resulting window, repositioned to minimize the dirtied
   * region. If the rewrite is empty (zero length, empty replacement), calls
   * {@link #advance()} instead.
   */
  public NGramWindow rewrite(final int length, final ImmutableList<SymbolPair> replacement) {
    Preconditions.checkArgument(length >= 0, "deletion length must be nonnegative.");

    if (length == 0 && replacement.isEmpty()) {
      return advance();
    }

    final int rewriteStart = position - length;
    if (rewriteStart < 0) {
      throw new IllegalArgumentException(
          String.format("Cannot rewrite length %s before position %s < %s.", length, position, length));
    }

    val replacementBuilder = ImmutableList.<SymbolPair>builder();

    // There may be some opportunity for optimization here, which should be pretty
    // straightforward if it turns out to be warranted.
    replacementBuilder.addAll(symbolPairs.subList(0, rewriteStart));
    replacementBuilder.addAll(replacement);
    replacementBuilder.addAll(symbolPairs.subList(position, symbolPairs.size()));

    return new NGramWindow(this, replacementBuilder.build(), Math.max(0, rewriteStart - config.ahead));
  }

  public int length() {
    return symbolPairs.size();
  }

  public Node single() {
    if (symbolPairs.size() != 1) {
      throw new IllegalStateException(String.format("size != 1: %s", debug()));
    }
    return symbolPairs.get(0).value;
  }

  public NGramWindow reset(final int behind, final int ahead) {
    return new NGramWindow(this, symbolPairs, 0, new Config(behind, ahead));
  }

  private static String toString(final Iterable<SymbolPair> symbolPairs) {
    val parts = new ArrayList<String>();
    val sb = new StringBuilder();

    for (val symbolPair : symbolPairs) {
      if (symbolPair.symbol() != null && symbolPair.value() != null
          && symbolPair.symbol()
              .getValue() == KnowledgeBase.Common.codePoint
          && symbolPair.value()
              .getValue() instanceof Integer) {
        if (sb.length() == 0) {
          sb.append('"');
        }
        sb.appendCodePoint((int) symbolPair.value()
            .getValue());
      } else {
        if (sb.length() > 0) {
          sb.append('"');
          parts.add(sb.toString());
          sb.setLength(0);
        }

        sb.append('(')
            .append(symbolPair.symbol())
            .append(", ")
            .append(symbolPair.value());
        if (symbolPair.value() != null && symbolPair.value()
            .getValue() instanceof Integer
            && !Character.isISOControl((int) symbolPair.value()
                .getValue())) {
          sb.append("/'")
              .appendCodePoint((int) symbolPair.value()
                  .getValue())
              .append("'");
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
    // Stringizing lazily could be misleading if nodes contain mutable state, but on
    // the other hand stringizing eagerly can be pretty expensive.

    val history = new ArrayList<String>(MAX_HISTORY);
    NGramWindow ancestor = parent;
    List<SymbolPair> sequence = symbolPairs;

    while (history.size() < MAX_HISTORY && ancestor != null) {
      if (ancestor.symbolPairs != sequence) {
        sequence = ancestor.symbolPairs;
        history.add(toString(sequence));
      }
      ancestor = ancestor.parent;
    }

    return String.format("%s\n    History: %s", this, String.join("\n    ", history));
  }
}