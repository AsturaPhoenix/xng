package ai.xng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import lombok.val;

public class NormalDistributionTest {
    @Test
    public void testDefault() {
        val distribution = new NormalDistribution(1);
        assertEquals(1, distribution.generate(), 0);
    }

    @Test
    public void testAddToMean() {
        val distribution = new NormalDistribution(1);
        distribution.add(1, 1);

        assertEquals(1, distribution.getMode(), 0);
        assertEquals(2, distribution.getWeight(), 0);

        assertEquals(1, distribution.getMin(), 0);
        assertEquals(1, distribution.getMax(), 0);

        assertEquals(1, distribution.generate(), 0);
    }

    @Test
    public void testSubtractFromWeightedMean() {
        val distribution = new NormalDistribution(1);
        distribution.add(1, 1);
        distribution.add(1, -1);

        assertEquals(1, distribution.getWeight(), 0);
        assertEquals(1, distribution.getMin(), 0);
        assertEquals(1, distribution.getMax(), 0);
    }

    @Test
    public void testSubtractFromExhaustedMean() {
        val distribution = new NormalDistribution(1);
        distribution.add(1, -1);

        assertEquals(NormalDistribution.MIN_WEIGHT, distribution.getWeight(), 0);
        float max = distribution.getMax();
        assertTrue(max > 1);
        assertEquals(2 - max, distribution.getMin(), Float.MIN_VALUE);

        float value = distribution.generate();
        if (value == 1) {
            value = distribution.generate();
        }

        assertTrue(value != 1);
        assertTrue(value >= distribution.getMin() && value <= distribution.getMax());
    }

    @Test
    public void testAddToSide() {
        val distribution = new NormalDistribution(0);
        distribution.add(1, 1);
        assertEquals(.5f, distribution.getMean(), Float.MIN_VALUE);
        assertTrue(distribution.getStandardDeviation() > 0);
        assertTrue(distribution.getMin() < 0);
        assertTrue(distribution.getMax() > 1);
    }
}
