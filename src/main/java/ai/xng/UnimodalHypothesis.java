package ai.xng;

import java.io.Serializable;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A hypothetical unimodal distribution. This distribution keeps track of
 * outliers that contradict the distribution.
 * <p>
 * Additionally, below a critical support level, the peak of the distribution
 * will degrade towards zero.
 */
public class UnimodalHypothesis implements Distribution, Serializable {
  /**
   * Spread basis with no evidence.
   */
  private static final float DEFAULT_SPREAD_BASIS = .2f;
  private static final float CRITICAL_SUPPORT = .5f;

  private static class Bucket implements Serializable {
    float mean, weight;

    float distributeWeight(final float delta, final float min) {
      final float naive = weight + delta;
      if (naive >= min) {
        weight = naive;
        return 0;
      } else {
        weight = min;
        return naive - min;
      }
    }
  }

  private final Bucket lower = new Bucket(), core = new Bucket(), upper = new Bucket();
  private final ReadWriteLock lock = new ReentrantReadWriteLock(false);

  @Override
  public String toString() {
    lock.readLock()
        .lock();
    try {
      return String.format(
          "μ = %.4g, w = %.2f (left tail: μ = %.4g, w = %.2f; core: μ = %.4g, w = %.2f; right tail: μ = %.4g, w = %.2f)",
          getMode(), getWeight(), lower.mean, lower.weight, core.mean, core.weight, upper.mean, upper.weight);
    } finally {
      lock.readLock()
          .unlock();
    }
  }

  @Override
  public float getMode() {
    return (lower.mean * lower.weight + core.mean * core.weight + upper.mean * upper.weight)
        / Math.max(getWeight(), CRITICAL_SUPPORT);
  }

  @Override
  public float getWeight() {
    return lower.weight + core.weight + upper.weight;
  }

  public UnimodalHypothesis() {
    this(0, 0);
  }

  public UnimodalHypothesis(final float mean) {
    set(mean);
  }

  public UnimodalHypothesis(final float mean, final float weight) {
    set(mean, weight);
  }

  @Override
  public void set(final float value, final float weight) {
    if (weight < 0) {
      throw new IllegalArgumentException("weight must be non-negative");
    }

    core.mean = value;
    lower.mean = core.mean - DEFAULT_SPREAD_BASIS;
    upper.mean = core.mean + DEFAULT_SPREAD_BASIS;
    core.weight = weight;
    lower.weight = upper.weight = 0;
  }

  private static float weightedAverage(final float valueA, final float weightA, final float valueB,
      final float weightB) {
    return (valueA * weightA + valueB * weightB) / (weightA + weightB);
  }

  @Override
  public void add(final float value, final float weight) {
    if (weight == 0)
      return;

    lock.writeLock()
        .lock();
    try {
      final Bucket tail, counterTail;
      if (value < core.mean) {
        tail = lower;
        counterTail = upper;
      } else {
        tail = upper;
        counterTail = lower;
      }

      final float ndev = tail.mean == core.mean ? 0 : Math.min((value - core.mean) / (tail.mean - core.mean), 1);
      final float tailWeightChange = ndev * weight;

      if (tailWeightChange > 0) {
        tail.mean = weightedAverage(tail.mean, tail.weight, value, tailWeightChange);
      }
      tail.weight = Math.max(0, tail.weight + tailWeightChange);

      final float coreWeightChange = weight - 2 * tailWeightChange;
      if (weight > 0 && coreWeightChange > 0) {
        core.mean = weightedAverage(core.mean, core.weight, value, coreWeightChange);
      }

      if (weight > 0) {
        float weightToDistribute = core.distributeWeight(coreWeightChange, 0);
        counterTail.distributeWeight(weightToDistribute, 0);
      } else if (coreWeightChange < 0) {
        float weightToDistribute = core.distributeWeight(coreWeightChange, 0);
        weightToDistribute = tail.distributeWeight(weightToDistribute, 0);
        counterTail.distributeWeight(weightToDistribute, 0);
      }

      if (core.weight == 0 && (lower.weight > 0 || upper.weight > 0)) {
        core.mean = weightedAverage(lower.mean, lower.weight, upper.mean, upper.weight);
      }

      if (core.mean <= lower.mean) {
        core.weight += lower.weight;
        lower.weight = 0;
      }
      if (core.mean >= upper.mean) {
        core.weight += upper.weight;
        upper.weight = 0;
      }

      if (lower.weight == 0) {
        lower.mean = core.mean - DEFAULT_SPREAD_BASIS;
      }
      if (upper.weight == 0) {
        upper.mean = core.mean + DEFAULT_SPREAD_BASIS;
      }

      assert core.mean > lower.mean && core.mean < upper.mean : String.format("%+.4g x %.2f to %s", value, weight,
          this);
    } finally {
      lock.writeLock()
          .unlock();
    }
  }

  @Override
  public float getMin() {
    return core.mean;
  }

  @Override
  public float getMax() {
    return core.mean;
  }

  @Override
  public float generate() {
    return getMode();
  }
}