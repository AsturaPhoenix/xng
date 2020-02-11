package ai.xng;

import static org.junit.Assert.fail;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

import org.junit.Test;

import io.reactivex.disposables.Disposable;
import lombok.RequiredArgsConstructor;
import lombok.val;

public class BanditTest {
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

        final KnowledgeBase kb;
        final Node choose;
        final List<BanditRecord> bandits;
        float reward = 0;
        int pulls = 0;
        List<BanditRecord> newPulls;

        public BinaryHarness(int banditCount) {
            kb = new KnowledgeBase();
            choose = kb.node();
            bandits = new ArrayList<BanditRecord>(banditCount);

            while (bandits.size() < banditCount) {
                val record = new BanditRecord(new BinaryBandit(random.nextDouble()), kb.node());
                bandits.add(record);
                record.subscription = record.node.rxActivate().forEach((activation) -> {
                    synchronized (this) {
                        onActivate(record, activation);
                    }
                });
            }
        }

        public void onActivate(final BanditRecord record, final Node.Activation activation) {
            ++pulls;

            float reinforcement;
            if (record.bandit.pull()) {
                ++reward;
                reinforcement = 1;
                System.out.printf("%.4g: =) (%.2f; %.2f)\n", record.bandit.p, reward / pulls, reinforcement);
            } else {
                reinforcement = reward / (reward - pulls);
                System.out.printf("%.4g: =( (%.2f; %.2f)\n", record.bandit.p, reward / pulls, reinforcement);
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
            val context = new Context(kb::node);
            choose.activate(context);
            context.rxActive().filter(active -> !active).blockingFirst();

            System.out.printf("Samples:\n%s\n",
                    bandits.stream().sorted((a, b) -> -Double.compare(a.bandit.p, b.bandit.p))
                            .map(record -> String.format("%.4g: %.4g", record.bandit.p,
                                    record.node.synapse.getLastEvaluation(context, choose).value))
                            .collect(Collectors.joining("\n")));

            if (newPulls.isEmpty()) {
                context.reinforce(Optional.empty(), Optional.empty(), -.1f);
                System.out.println(report());
            }

            return newPulls;
        }

        @Override
        public void onActivate(final BanditRecord record, final Node.Activation activation) {
            super.onActivate(record, activation);
            System.out.println(report());
        }

        public String report() {
            return bandits.stream().sorted((a, b) -> -Double.compare(a.bandit.p, b.bandit.p))
                    .map(record -> String.format("%.4g: %s", record.bandit.p,
                            record.node.synapse.getInputs().get(choose).getCoefficient()))
                    .collect(Collectors.joining("\n"));
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
            val context = new Context(kb::node);
            choose.activate(context);
            context.rxActive().filter(active -> !active).blockingFirst();

            System.out.printf("Samples:\n%s\n",
                    bandits.stream().sorted((a, b) -> -Double.compare(a.bandit.p, b.bandit.p))
                            .map(record -> new AbstractMap.SimpleEntry<>(record.bandit,
                                    record.node.synapse.getLastEvaluation(context, choose)))
                            .filter(entry -> entry.getValue() != null)
                            .map(entry -> String.format("%.4g: %.4g", entry.getKey().p, entry.getValue().value))
                            .collect(Collectors.joining("\n")));

            if (newPulls.isEmpty()) {
                bandits.get(random.nextInt(bandits.size())).node.activate(context);
                context.rxActive().filter(active -> !active).blockingFirst();
            }

            return newPulls;
        }
    }

    private void runSuite(final BinaryHarness harness) {
        val best = harness.bandits.stream().max((a, b) -> Double.compare(a.bandit.p, b.bandit.p)).get();

        int consecutiveBest = 0;
        double efficacy = 0;

        while (consecutiveBest < 100) {
            val pulls = harness.runTrial();
            if (pulls.size() == 1 && pulls.iterator().next() == best) {
                ++consecutiveBest;
            } else {
                consecutiveBest = 0;
            }

            System.out.println(harness.pulls);

            efficacy = harness.reward / harness.pulls / best.bandit.p;

            if (harness.pulls > 10000) {
                if (efficacy > .9) {
                    System.out.printf("Did not converge after %d pulls, but efficacy was %.2f (%.2f).\n", harness.pulls,
                            efficacy, harness.reward / harness.pulls);
                    return;
                } else {
                    fail(String.format("Did not converge after %d pulls with %.2f efficacy (%.2f).\n", harness.pulls,
                            efficacy, harness.reward / harness.pulls));
                }
            }
        }

        System.out.printf("Converged after around %d pulls with %.2f efficacy (%.2f).\n", harness.pulls, efficacy,
                harness.reward / harness.pulls);
    }

    /**
     * Implements Bernoulli Bandit by plugging a minimal network into a tailored
     * test harness.
     */
    @Test
    public void testMinimalExplicitBinaryBandit() {
        try (val harness = new ExplicitHarness(4)) {
            runSuite(harness);
        }
    }

    /**
     * Implements Bernoulli Bandit by plugging an empty network into a tailored test
     * harness.
     */
    @Test
    public void testHebbianBinaryBandit() {
        try (val harness = new HebbianHarness(4)) {
            runSuite(harness);
        }
    }
}
