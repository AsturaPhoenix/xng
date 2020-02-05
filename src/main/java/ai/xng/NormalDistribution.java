package ai.xng;

import java.io.Serializable;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.google.common.primitives.Floats;

import lombok.Getter;

import java.util.Random;

/**
 * A normal distribution, truncated to allow for optimization during synapse
 * processing.
 */
public class NormalDistribution implements Distribution, Serializable {
    private static final long serialVersionUID = -4582334729234682748L;
    /** Number of standard deviations to include on each side. */
    private static final float TRUNCATE_BEYOND = 3;
    private static final float SPREAD_EXPANSION = .2f;
    private static final float MAX_SPREAD = 1, MIN_WEIGHT = 1, DEFAULT_WEIGHT = 1;

    private final Random random;
    @Getter
    private float mean, standardDeviation, weight;
    private final ReadWriteLock lock = new ReentrantReadWriteLock(false);

    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return String.format("%.4g x %.4g (%.2f)", mean, standardDeviation, weight);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public float getMode() {
        return mean;
    }

    public NormalDistribution() {
        this(0);
    }

    public NormalDistribution(final float mean) {
        this(new Random(), mean);
    }

    public NormalDistribution(final Random random, final float mean) {
        this.random = random;
        set(mean);
    }

    @Override
    public void set(final float value) {
        mean = value;
        weight = DEFAULT_WEIGHT;
        standardDeviation = 0;
    }

    @Override
    public void add(final float value, float weight) {
        if (weight == 0)
            return;

        lock.writeLock().lock();
        try {
            final float dx = value - mean;

            if (weight < 0) {
                // For negative reinforcement, modulate by the mean-relative density to discount
                // samples with less basis in reality.
                if (standardDeviation > 0) {
                    final float x = dx / standardDeviation;
                    weight *= Math.exp(-x * x / 2);
                } else if (value != mean) {
                    return;
                }

                weight = Math.max(weight, -this.weight / 2);
            }

            float newWeight = this.weight + weight;
            standardDeviation = Math.min(
                    (float) Math.sqrt(Math.max(
                            (this.weight * standardDeviation * standardDeviation + weight * dx * dx) / newWeight, 0)),
                    MAX_SPREAD);
            mean = (this.weight * mean + weight * value) / newWeight;
            if (newWeight < MIN_WEIGHT) {
                standardDeviation += (MIN_WEIGHT - newWeight) * SPREAD_EXPANSION;
                this.weight = MIN_WEIGHT;
            } else {
                this.weight = newWeight;
            }
        } finally

        {
            lock.writeLock().unlock();
        }
    }

    @Override
    public float getMin() {
        return mean - TRUNCATE_BEYOND * standardDeviation;
    }

    @Override
    public float getMax() {
        return mean + TRUNCATE_BEYOND * standardDeviation;
    }

    @Override
    public float generate() {
        return Floats.constrainToRange((float) random.nextGaussian() * getStandardDeviation() + mean, getMin(),
                getMax());
    }
}
