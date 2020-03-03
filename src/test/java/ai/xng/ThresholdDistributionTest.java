package ai.xng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import lombok.val;

public class ThresholdDistributionTest {
    @Test
    public void testDefault() {
        val distribution = new ThresholdDistribution(1);
        assertEquals(1, distribution.generate(), 0);
    }

    @Test
    public void testAddToThreshold() {
        val distribution = new ThresholdDistribution(1);
        distribution.add(1, 1);

        assertEquals(1, distribution.getMode(), 0);
        assertEquals(2, distribution.getWeight(), 0);

        assertEquals(1, distribution.getMin(), 0);
        assertEquals(1, distribution.getMax(), 0);

        assertEquals(1, distribution.generate(), 0);
    }

    @Test
    public void testSubtractFromWeightedMean() {
        val distribution = new ThresholdDistribution(1);
        distribution.add(1, 1);
        distribution.add(1, -1);

        assertEquals(1, distribution.getMode(), 0);
        assertTrue(distribution.getWeight() >= 1);
        assertTrue(distribution.getMin() < 1);
        assertTrue(distribution.getMax() > 1);
    }

    /**
     * The average of values sampled from a biased threshold distribution should
     * still be the threshold. However, the count of values on each side of the
     * threshold should be biased.
     */
    @Test
    public void biasTest() {
        val distribution = new ThresholdDistribution(1);
        distribution.add(-.5f, 1.f / 3);
        // Add a negative sample to make spread nonzero.
        distribution.add(distribution.getMode(), -.1f);

        float total = 0;
        int below = 0, above = 0;
        for (int i = 0; i < 10000; ++i) {
            final float value = distribution.generate();
            total += value;
            if (value >= distribution.getThreshold()) {
                ++above;
            } else {
                ++below;
            }
        }

        assertEquals(distribution.getThreshold(), total / 10000, .02f);
        assertEquals(distribution.getBias(), (above - below) / 10000.f, .02f);
    }
}
