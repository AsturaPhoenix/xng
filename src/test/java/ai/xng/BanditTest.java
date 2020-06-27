// package ai.xng;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.junit.jupiter.api.Assertions.fail;

// import java.util.ArrayList;
// import java.util.Collection;
// import java.util.List;
// import java.util.Random;
// import java.util.function.Supplier;

// import org.junit.jupiter.api.Test;

// import io.reactivex.Completable;
// import lombok.RequiredArgsConstructor;
// import lombok.val;

// public class BanditTest {
// private final Random random = new Random();
// private static final int CONSECUTIVE_BEST_REQUIRED = 100, MAX_PULLS = 10000;

// /**
// * A one-armed bandit that has a probability {@link #p} of producing a Boolean
// * reward.
// */
// @RequiredArgsConstructor
// private class BinaryBandit {
// private final double p;
// private int pulls, reward;

// public boolean pull() {
// ++pulls;
// final boolean result = random.nextDouble() < p;
// if (result) {
// ++reward;
// }
// return result;
// }

// @Override
// public String toString() {
// return String.format("p: %.4g, pulls: %s, observed utility: %.4g", p, pulls,
// (float) reward / pulls);
// }
// }

// private abstract class BinaryHarness implements AutoCloseable {
// @RequiredArgsConstructor
// protected class BanditNode extends SynapticNode {
// private static final long serialVersionUID = 1L;

// final BinaryBandit bandit;

// @Override
// protected Completable onActivate(Context context) {
// ++pulls;

// final float reinforcement;
// val pull = bandit.pull();
// if (pull) {
// ++reward;
// reinforcement = 1;
// } else {
// reinforcement = (float) reward / (reward - pulls);
// }

// context.reinforce(reinforcement).join();
// newPulls.add(bandit);

// return null;
// }
// }

// final KnowledgeBase kb;
// final Node choose;
// final List<BanditNode> bandits;
// float reward = 0;
// int pulls = 0;
// List<BinaryBandit> newPulls;

// public BinaryHarness(int banditCount) {
// kb = new KnowledgeBase();
// choose = new Node();
// choose.comment = "choose";
// bandits = new ArrayList<>(banditCount);

// while (bandits.size() < banditCount) {
// val node = new BanditNode(new BinaryBandit(random.nextDouble()));
// node.comment = String.format("%.4g", node.bandit.p);
// bandits.add(node);
// }
// }

// public abstract Collection<BinaryBandit> runTrial();

// public float utility() {
// return (float) reward / pulls;
// }

// @Override
// public void close() {
// kb.close();
// }

// public void report() {
// for (val banditNode : bandits) {
// System.out.printf("%s (%s)\n", banditNode, banditNode.bandit);
// for (val line : banditNode.getSynapse().toString().split("\n")) {
// System.out.printf("\t%s\n", line);
// }
// }
// }
// }

// /**
// * This harness explicitly wires the choose node to the bandit nodes at the
// * beginning of the test.
// */
// private class ExplicitHarness extends BinaryHarness {
// public ExplicitHarness(int banditCount) {
// super(banditCount);

// for (val node : bandits) {
// choose.then(node);
// }
// }

// @Override
// public Collection<BinaryBandit> runTrial() {
// newPulls = new ArrayList<>();
// val context = kb.newContext();
// choose.activate(context);
// context.blockUntilIdle();

// if (newPulls.isEmpty()) {
// context.reinforce(-.1f).join();
// }

// return newPulls;
// }
// }

// /**
// * This harness activates a random bandit if none is selected by the choose
// * node.
// */
// private class HebbianHarness extends BinaryHarness {
// public HebbianHarness(int banditCount) {
// super(banditCount);
// }

// @Override
// public Collection<BinaryBandit> runTrial() {
// newPulls = new ArrayList<>();
// val context = kb.newContext();
// choose.activate(context);
// context.blockUntilIdle();

// if (newPulls.isEmpty()) {
// context.reinforce(-1).join();
// final Node bandit = bandits.get(random.nextInt(bandits.size()));
// bandit.activate(context);
// context.blockUntilIdle();
// }

// return newPulls;
// }
// }

// private void runSuite(final BinaryHarness harness) {
// val best = harness.bandits.stream().map(node -> node.bandit).max((a, b) ->
// Double.compare(a.p, b.p)).get();

// int consecutiveBest = 0, consecutiveEmpty = 0;
// double efficacy = 0;

// try {
// while (consecutiveBest < CONSECUTIVE_BEST_REQUIRED) {
// val pulls = harness.runTrial();
// if (pulls.size() == 1 && pulls.iterator().next() == best) {
// ++consecutiveBest;
// } else {
// consecutiveBest = 0;
// if (pulls.isEmpty()) {
// assertThat(++consecutiveEmpty).as("consecutive empty runs").isLessThan(1000);
// } else {
// consecutiveEmpty = 0;
// }
// }

// efficacy = harness.utility() / best.p;

// if (harness.pulls > MAX_PULLS) {
// if (efficacy > .9) {
// System.out.printf("Did not converge after %d pulls, but efficacy was %.2f
// (%.2f).\n", harness.pulls,
// efficacy, harness.utility());
// return;
// } else {
// fail(String.format("Did not converge after %d pulls with %.2f efficacy
// (%.2f).", harness.pulls, efficacy,
// harness.utility()));
// }
// }
// }

// System.out.printf("Converged after around %d pulls with %.2f efficacy
// (%.2f).\n", harness.pulls, efficacy,
// harness.utility());
// } finally {
// }
// }

// private void runBattery(Supplier<BinaryHarness> harnessFactory, int trials,
// int allowedFailures) {
// int failures = 0;
// for (int i = 0; i < trials; ++i) {
// try (val harness = harnessFactory.get()) {
// try {
// runSuite(harness);
// } catch (Throwable e) {
// e.printStackTrace();
// ++failures;
// } finally {
// harness.report();
// }
// }
// System.gc();
// }
// assertThat(failures).isLessThanOrEqualTo(allowedFailures);
// }

// /**
// * Implements Bernoulli Bandit by plugging a minimal network into a tailored
// * test harness.
// */
// @Test
// public void testMinimalExplicitBinaryBandit() {
// runBattery(() -> new ExplicitHarness(5), 20, 1);
// }

// /**
// * Implements Bernoulli Bandit by plugging an empty network into a tailored
// test
// * harness.
// */
// @Test
// public void testHebbianBinaryBandit() {
// runBattery(() -> new HebbianHarness(5), 20, 1);
// }
// }
