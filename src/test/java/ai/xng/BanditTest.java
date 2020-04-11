package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.reactivex.disposables.Disposable;
import lombok.RequiredArgsConstructor;
import lombok.val;

public class BanditTest {
    /**
     * TODO: This is a hack while Hebbian learning skews exploration in favor of
     * activation.
     */
    private static final float NEGATIVE_BIAS = .3f;

    private final Random random = new Random();

    /**
     * A one-armed bandit that has a probability {@link #p} of producing a Boolean
     * reward.
     */
    @RequiredArgsConstructor
    private class BinaryBandit {
        private final double p;

        public boolean pull() {
            return random.nextDouble() < p;
        }
    }

    private abstract class BinaryHarness implements AutoCloseable {
        @RequiredArgsConstructor
        protected class BanditRecord {
            final BinaryBandit bandit;
            final Node node;
            Disposable subscription;
        }

        final Object lock = new Object();

        final KnowledgeBase kb;
        final Node choose;
        final List<BanditRecord> bandits;
        float reward = 0;
        int pulls = 0;
        List<BanditRecord> newPulls;

        public BinaryHarness(int banditCount) {
            kb = new KnowledgeBase();
            choose = kb.node();
            choose.comment = "choose";
            bandits = new ArrayList<BanditRecord>(banditCount);

            while (bandits.size() < banditCount) {
                val record = new BanditRecord(new BinaryBandit(random.nextDouble()), kb.node());
                bandits.add(record);
                record.node.comment = String.format("%.4g", record.bandit.p);
                record.subscription = record.node.rxActivate().subscribe((activation) -> {
                    // It'd be nice to eschew this lock and use observeOn instead, but this lets us
                    // block context idle until we've finished processing.
                    synchronized (lock) {
                        onActivate(record, activation);
                    }
                }, Assertions::fail);
            }
        }

        public void onActivate(final BanditRecord record, final Node.Activation activation) {
            ++pulls;

            float reinforcement;
            if (record.bandit.pull()) {
                ++reward;
                reinforcement = 1;
            } else {
                reinforcement = reward / (reward - pulls) - NEGATIVE_BIAS;
            }

            activation.context.reinforce(Optional.empty(), Optional.empty(), reinforcement);
            newPulls.add(record);
        }

        public abstract Collection<BanditRecord> runTrial();

        @Override
        public void close() {
            for (val record : bandits) {
                record.subscription.dispose();
            }
            kb.close();
        }
    }

    /**
     * This harness explicitly wires the choose node to the bandit nodes at the
     * beginning of the test.
     */
    private class ExplicitHarness extends BinaryHarness {
        public ExplicitHarness(int banditCount) {
            super(banditCount);

            for (val record : bandits) {
                choose.then(record.node);
            }
        }

        @Override
        public Collection<BanditRecord> runTrial() {
            newPulls = new ArrayList<>();
            val context = kb.newContext();
            choose.activate(context);
            context.blockUntilIdle();

            if (newPulls.isEmpty()) {
                context.reinforce(Optional.empty(), Optional.empty(), -.1f);
            }

            return newPulls;
        }
    }

    /**
     * This harness activates a random bandit if none is selected by the choose
     * node.
     */
    private class HebbianHarness extends BinaryHarness {
        public HebbianHarness(int banditCount) {
            super(banditCount);
        }

        @Override
        public Collection<BanditRecord> runTrial() {
            newPulls = new ArrayList<>();
            val context = kb.newContext();
            choose.activate(context);
            context.blockUntilIdle();

            if (newPulls.isEmpty()) {
                bandits.get(random.nextInt(bandits.size())).node.activate(context);
                context.blockUntilIdle();
            }

            return newPulls;
        }
    }

    private void report(final BinaryHarness harness) {
        for (val bandit : harness.bandits) {
            System.out.println(bandit.node);
            for (val line : bandit.node.getSynapse().toString().split("\n")) {
                System.out.println("\t" + line);
            }
        }
    }

    private void runSuite(final BinaryHarness harness) {
        val best = harness.bandits.stream().max((a, b) -> Double.compare(a.bandit.p, b.bandit.p)).get();

        int consecutiveBest = 0;
        double efficacy = 0;

        try {
            while (consecutiveBest < 100) {
                val pulls = harness.runTrial();
                if (pulls.size() == 1 && pulls.iterator().next() == best) {
                    ++consecutiveBest;
                } else {
                    consecutiveBest = 0;
                }

                efficacy = harness.reward / harness.pulls / best.bandit.p;

                if (harness.pulls > 10000) {
                    if (efficacy > .9) {
                        System.out.printf("Did not converge after %d pulls, but efficacy was %.2f (%.2f).\n",
                                harness.pulls, efficacy, harness.reward / harness.pulls);
                        return;
                    } else {
                        fail(String.format("Did not converge after %d pulls with %.2f efficacy (%.2f).", harness.pulls,
                                efficacy, harness.reward / harness.pulls));
                    }
                }
            }

            System.out.printf("Converged after around %d pulls with %.2f efficacy (%.2f).\n", harness.pulls, efficacy,
                    harness.reward / harness.pulls);
        } finally {
        }
    }

    private void runBattery(Supplier<BinaryHarness> harnessFactory, int trials, int allowedFailures) {
        int failures = 0;
        for (int i = 0; i < trials; ++i) {
            try (val harness = harnessFactory.get()) {
                try {
                    runSuite(harness);
                } catch (Throwable e) {
                    e.printStackTrace();
                    ++failures;
                } finally {
                    report(harness);
                }
            }
            System.gc();
        }
        assertThat(failures).isLessThanOrEqualTo(allowedFailures);
    }

    /**
     * Implements Bernoulli Bandit by plugging a minimal network into a tailored
     * test harness.
     */
    @Test
    public void testMinimalExplicitBinaryBandit() {
        runBattery(() -> new ExplicitHarness(4), 2, 1);
    }

    /**
     * Implements Bernoulli Bandit by plugging an empty network into a tailored test
     * harness.
     */
    @Test
    public void testHebbianBinaryBandit() {
        runBattery(() -> new HebbianHarness(4), 2, 1);
    }
}
