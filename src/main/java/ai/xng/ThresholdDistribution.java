package ai.xng;

import java.io.Serializable;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import lombok.Getter;

import java.util.Random;

public class ThresholdDistribution implements Distribution, Serializable {
    private static final long serialVersionUID = -2101048901598646069L;
    public static final float SPREAD_FACTOR = .25f, TRUNCATE_BEYOND = 3, DEFAULT_WEIGHT = 1, MIN_INERTIA = 10;

    private final Random random;
    @Getter
    private float threshold, weightAbove, weightBelow;
    private final ReadWriteLock lock = new ReentrantReadWriteLock(false);

    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return String.format("%.4g x %.4g (%.2f: %.2f-%.2f)", threshold, getSpread(), getBias(), weightBelow,
                    weightAbove);
        } finally {
            lock.readLock().unlock();
        }
    }

    public float getBias() {
        lock.readLock().lock();
        try {
            return 2 * weightAbove / (weightBelow + weightAbove) - 1;
        } finally {
            lock.readLock().unlock();
        }
    }

    public float getWeight() {
        lock.readLock().lock();
        try {
            return Math.max(weightBelow, weightAbove);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public float getMode() {
        return threshold;
    }

    public ThresholdDistribution() {
        this(0);
    }

    public ThresholdDistribution(final float threshold) {
        this(new Random(), threshold);
    }

    public ThresholdDistribution(final Random random, final float threshold) {
        this.random = random;
        set(threshold);
    }

    @Override
    public void set(final float value) {
        lock.writeLock().lock();
        try {
            threshold = value;
            weightAbove = DEFAULT_WEIGHT;
            weightBelow = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public float getSpread() {
        lock.readLock().lock();
        try {
            return SPREAD_FACTOR * Math.min(weightBelow, weightAbove) / (float) Math.sqrt(getWeight());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void add(final float value, final float weight) {
        if (weight == 0)
            return;

        lock.writeLock().lock();
        try {
            final float inertia = MIN_INERTIA + getWeight();
            if (weight > -inertia + 1) {
                threshold = (inertia * threshold + weight * value) / (inertia + weight);
            }

            if (value < threshold || value == threshold && weightBelow > weightAbove) {
                weightBelow = Math.max(0, weightBelow + weight);
                weightAbove = Math.max(0, weightAbove - weight);
            } else if (value > threshold || value == threshold && weightBelow < weightAbove) {
                weightBelow = Math.max(0, weightBelow - weight);
                weightAbove = Math.max(0, weightAbove + weight);
            } else {
                assert value == threshold;
                assert weightBelow == weightAbove;
                weightBelow = Math.max(0, weightBelow + weight / 2);
                weightAbove = Math.max(0, weightAbove + weight / 2);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public float getMin() {
        lock.readLock().lock();
        try {
            return threshold - getSpread() * TRUNCATE_BEYOND;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public float getMax() {
        lock.readLock().lock();
        try {
            return threshold + getSpread() * TRUNCATE_BEYOND;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public float generate() {
        lock.readLock().lock();
        try {
            float value;
            if (random.nextFloat() >= (getBias() + 1) / 2) {
                value = threshold - (float) Math.abs(random.nextGaussian()) * getSpread();
            } else {
                value = threshold + (float) Math.abs(random.nextGaussian()) * getSpread();
            }
            return value < getMin() || value > getMax() ? threshold : value;
        } finally {
            lock.readLock().unlock();
        }
    }
}
