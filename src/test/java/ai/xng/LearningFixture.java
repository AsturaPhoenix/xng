package ai.xng;

import org.junit.jupiter.api.Assertions;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A test fixture that requires a number of consecutive successes within a
 * maximum number of total runs.
 */
@RequiredArgsConstructor
public class LearningFixture {
  private final int requiredConsecutiveSuccesses, maxRuns;
  @Getter
  private int consecutiveSuccesses, runs;
  private String lastFailure;

  public void pass() {
    ++consecutiveSuccesses;
    ++runs;
  }

  public void fail() {
    fail(null);
  }

  public void fail(final String description) {
    lastFailure = description;
    consecutiveSuccesses = 0;
    ++runs;
  }

  public boolean shouldContinue() {
    if (runs >= maxRuns) {
      Assertions.fail(String.format("Required at least %s consecutive successes by %s runs.%s",
          requiredConsecutiveSuccesses, maxRuns, lastFailure == null ? "" : "Last failure: " + lastFailure));
    }
    return consecutiveSuccesses < requiredConsecutiveSuccesses;
  }

  public void reinforce(final boolean pass, final Context context, final float passWeight, final float failWeight) {
    reinforce(pass, context, passWeight, failWeight, null);
  }

  public void reinforce(final boolean pass, final Context context, final float passWeight, final float failWeight,
      final String description) {
    if (pass) {
      pass();
      context.reinforce(passWeight).join();
    } else {
      fail(description);
      context.reinforce(failWeight).join();
    }
  }
}
