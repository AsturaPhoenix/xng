// package ai.xng;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.junit.jupiter.api.Assertions.assertEquals;

// import org.junit.jupiter.api.Test;

// import lombok.val;

// public class DistributionTest {
// @Test
// public void testDefault() {
// assertThat(new UnimodalHypothesis(Synapse.THRESHOLD +
// Node.THRESHOLD_MARGIN).getMin())
// .isGreaterThanOrEqualTo(Synapse.THRESHOLD);
// assertThat(new UnimodalHypothesis(Synapse.THRESHOLD -
// Node.THRESHOLD_MARGIN).getMax())
// .isLessThanOrEqualTo(Synapse.THRESHOLD);
// }

// @Test
// public void testBounding() {
// val distribution = new UnimodalHypothesis();
// for (int i = 0; i < 1000; ++i) {
// assertThat(distribution.generate()).isBetween(distribution.getMin(),
// distribution.getMax());
// }
// }

// @Test
// public void testAddToMean() {
// val distribution = new UnimodalHypothesis(1);
// distribution.add(1, 1);
// assertEquals(1, distribution.getMode(), 0);
// }

// @Test
// public void testSubtractFromWeightedMean() {
// val distribution = new UnimodalHypothesis(1);
// distribution.add(1, -1);
// assertEquals(1, distribution.getMode(), 0);
// }

// @Test
// public void testAddToTail() {
// val distribution = new UnimodalHypothesis(0);
// distribution.set(0, 2);
// distribution.add(1, 1);
// // Previous implementations ensured that adding to a tail would honor
// weighted
// // average. However, in practice it seems that subsuming core samples
// produces
// // better results.
// assertEquals(.5f, distribution.getMode(), Float.MIN_VALUE,
// distribution.toString());
// }

// /**
// * Ensures that a large number of weak samples does not obliterate a small
// * number of strong samples.
// */
// @Test
// public void testDeathByAThousandPositiveCuts() {
// val distribution = new UnimodalHypothesis();

// distribution.add(1, 1);
// for (int i = 0; i < 1000; ++i) {
// distribution.add(-1, .001f);
// }

// // Include a little tolerance since the distribution is somewhat simplified.
// assertThat(distribution.getMode()).isGreaterThanOrEqualTo(-.1f);
// }

// /**
// * Ensures that repeated negative reinforcement of self-generated values does
// * not destabilize the distribution critically.
// */
// @Test
// public void testCoreNegativeStability() {
// val distribution = new UnimodalHypothesis();
// for (int i = 0; i < 10000; ++i) {
// distribution.add(distribution.generate(), -1);
// }
// assertThat(distribution.getMode()).isBetween(-1e10f, 1e10f);
// }

// /**
// * Ensures that repeated negative reinforcement of long tail values does not
// * distabilize the distribution critically.
// */
// @Test
// public void testTailNegativeStability() {
// val distribution = new UnimodalHypothesis();
// for (int i = 0; i < 1000; ++i) {
// distribution.add(-1000, -1);
// }
// assertThat(distribution.getMode()).isBetween(-1e10f, 1e10f);
// }

// @Test
// public void testDepletedRespondsToNewSample() {
// val distribution = new UnimodalHypothesis();
// distribution.set(0, 0);
// distribution.add(1.2f, 1);
// distribution.add(.8f, -1);
// assertEquals(1.2f, distribution.generate());
// }

// @Test
// public void testTwiceDepletedRespondsToNewSample() {
// val distribution = new UnimodalHypothesis();
// distribution.set(1, 0);
// distribution.add(.8f, 1);
// distribution.add(.8f, -1);
// distribution.add(1.2f, 1);
// distribution.add(.8f, -1);
// assertEquals(1.2f, distribution.generate());
// }
// }
