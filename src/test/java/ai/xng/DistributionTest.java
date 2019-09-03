package ai.xng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class DistributionTest {
    @Test
    public void testNoSamples() {
        final Distribution distribution = new Distribution(1);
        assertEquals(1, distribution.generate(), 0);
    }

    @Test
    public void testSingleSample() {
        final Distribution distribution = new Distribution();
        distribution.add(2, 1);
        assertEquals(2, distribution.getMin(), 0);
        assertEquals(2, distribution.getMax(), 0);
        assertEquals(2, distribution.getMode(), 0);
        assertEquals(2, distribution.generate(), 0);
    }

    @Test
    public void testTwoSameSamples() {
        final Distribution distribution = new Distribution();
        distribution.add(2, 1);
        distribution.add(2, .5f);
        assertEquals(2, distribution.getMin(), 0);
        assertEquals(2, distribution.getMax(), 0);
        assertEquals(2, distribution.getMode(), 0);
        assertEquals(2, distribution.generate(), 0);
    }

    @Test
    public void testTwoEvenSamples() {
        final Distribution distribution = new Distribution();
        distribution.add(1, 1);
        distribution.add(2, 1);
        assertEquals(1, distribution.getMin(), 0);
        assertEquals(2, distribution.getMax(), 0);
        assertEquals(1.5f, distribution.getMode(), 0);
        final float gen = distribution.generate();
        assertTrue(gen >= distribution.getMin());
        assertTrue(gen <= distribution.getMax());
    }

    @Test
    public void testObliteration() {
        final Distribution distribution = new Distribution();
        distribution.add(1, 1);
        distribution.add(2, 1);
        distribution.add(1.5f, -1);
        // Naively, the mode may also be recalculated to be NaN. We don't want
        // that because it's not useful.
        assertEquals(1.5f, distribution.getMin(), 0);
        assertEquals(1.5f, distribution.getMax(), 0);
        assertEquals(1.5f, distribution.getMode(), 0);
        assertEquals(1.5f, distribution.generate(), 0);
    }

    /**
     * There's an edge case where the obliteration of the mode can result in a
     * mode recalculation that attempts to bisect against an asymmetric
     * infinity.
     */
    @Test
    public void testInfiniteSideObliteration() {
        final Distribution distribution = new Distribution();
        distribution.add(1, 1);
        distribution.add(0, -1);
        distribution.add(1, -1);
        float gen = distribution.generate();
        assertFalse(gen == Float.POSITIVE_INFINITY);
        assertFalse(gen == Float.NaN);
    }

    @Test
    public void testTruncateSide() {
        final Distribution distribution = new Distribution();
        distribution.add(1, 1);
        distribution.add(2, 1);
        distribution.add(1.25f, -1);
        assertEquals(1.25f, distribution.getMin(), 0);
        assertEquals(2, distribution.getMax(), 0);
        assertEquals(1.625f, distribution.getMode(), 0);
        final float gen = distribution.generate();
        assertTrue(gen >= 1.25f);
        assertTrue(gen <= 2);
    }

    private static final int N = 10000;
    private static final float E = .1f;

    @Test
    public void testDistributionWithMode() {
        final Distribution distribution = new Distribution(new Random(0), 0);
        distribution.add(1, 1);
        distribution.add(2, 2);
        distribution.add(4, 3);
        distribution.add(5, 1);
        assertEquals(1, distribution.getMin(), 0);
        assertEquals(5, distribution.getMax(), 0);
        assertEquals(4, distribution.getMode(), 0);

        final List<Float> samples = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            samples.add(distribution.generate());
        }
        samples.sort(null);

        assertEquals(1, samples.get(0), E);
        assertEquals(2, samples.get(N / 7), E);
        assertEquals(4, samples.get(3 * N / 7), E);
        assertEquals(4, samples.get(6 * N / 7), E);
        assertEquals(5, samples.get(N - 1), E);
    }

    @Test
    public void testDistributionBisectedMode() {
        final Distribution distribution = new Distribution(new Random(0), 0);
        distribution.add(1, 1);
        distribution.add(2, 2);
        distribution.add(4, 3);
        distribution.add(5, 3);
        distribution.add(6, 1);
        assertEquals(1, distribution.getMin(), 0);
        assertEquals(6, distribution.getMax(), 0);
        assertEquals(4.5f, distribution.getMode(), 0);

        final List<Float> samples = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            samples.add(distribution.generate());
        }
        samples.sort(null);

        assertEquals(1, samples.get(0), E);
        assertEquals(2, samples.get(N / 10), E);
        assertEquals(4, samples.get(3 * N / 10), E);
        assertEquals(5, samples.get(9 * N / 10), E);
        assertEquals(6, samples.get(N - 1), E);
    }
}
