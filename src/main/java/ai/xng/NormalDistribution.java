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
    private static final int FIT_ITERATIONS = 5;
    public static final float MAX_SPREAD = 1, MIN_WEIGHT = 1, DEFAULT_WEIGHT = 1;

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

    private float relPdf(final float value) {
        if (standardDeviation == 0) {
            return value == mean ? 1 : 0;
        } else {
            final float x = (value - mean) / standardDeviation;
            return (float) Math.exp(-x * x / 2);
        }
    }

    private float pdf(final float value) {
        return standardDeviation == 0 ? relPdf(value) : relPdf(value) / (standardDeviation * 2 * (float) Math.PI);
    }

    public float getDensity(final float value) {
        return value < getMin() || value > getMax() ? 0 : pdf(value);
    }

    private void fitSpread(final float value, final float density) {
        final float dx = value - mean;

        final float s0 = standardDeviation;

        // The pdf peaks at value for a given mean when the value is 1 standard
        // deviation away. If we fall short there, that's the best we can do until the
        // mean moves closer.
        standardDeviation = Math.abs(dx);
        if (pdf(value) <= density) {
            return;
        }

        standardDeviation = s0;

        for (int i = 0; i < FIT_ITERATIONS; ++i) {
            float dpds;
            if (standardDeviation > 0) {
                dpds = (dx * dx / (standardDeviation * standardDeviation) - 1) / standardDeviation;
                if (dpds == 0) {
                    // err on the side of narrowing (this can only happen for lowering density)
                    dpds = 1000;
                }
            } else {
                dpds = dx == 0 ? -1000 : 1000;
            }

            standardDeviation = Floats.constrainToRange(standardDeviation + (density - pdf(value)) / dpds,
                    dx / TRUNCATE_BEYOND, MAX_SPREAD);
        }
    }

    @Override
    public void add(final float value, float weight) {
        if (weight == 0)
            return;

        lock.writeLock().lock();
        try {
            final float sampleWeight = this.weight * pdf(value);

            // Limit negative weights when current weight is low.
            if (this.weight + weight < MIN_WEIGHT / 2) {
                weight = MIN_WEIGHT / 2 - this.weight;
            }

            float dw;
            if (standardDeviation == 0) {
                dw = value == mean ? weight : -weight;
            } else {
                dw = weight * Math.max(1 - Math.abs(value - mean) / standardDeviation, -1);
            }

            mean = (this.weight * mean + weight * value) / (this.weight + weight);
            fitSpread(value, (sampleWeight + weight) / Math.max(this.weight + weight, MIN_WEIGHT));
            this.weight = Math.max(this.weight + dw, MIN_WEIGHT);
        } finally {
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
        final float value = (float) random.nextGaussian() * getStandardDeviation() + mean;
        return value < getMin() || value > getMax() ? mean : value;
    }
}
