package ai.xng;

import static org.junit.Assert.fail;

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

    private class BinaryHarness implements AutoCloseable {
        @RequiredArgsConstructor
        private class BanditRecord {
            final BinaryBandit bandit;
            final Node node;
            Disposable subscription;
        }

        private final KnowledgeBase kb;
        private final Node choose;
        final List<BanditRecord> bandits;
        private float reward = 0;
        int pulls = 0;
        List<BanditRecord> newPulls;

        public BinaryHarness(int banditCount) {
            kb = new KnowledgeBase();
            choose = kb.node();
            bandits = new ArrayList<BanditRecord>(banditCount);

            while (bandits.size() < banditCount) {
                val record = new BanditRecord(new BinaryBandit(random.nextDouble()), kb.node());
                bandits.add(record);
                choose.then(record.node);
                record.subscription = record.node.rxActivate().forEach((activation) -> {
                    synchronized (this) {
                        ++pulls;

                        float reinforcement;
                        if (record.bandit.pull()) {
                            ++reward;
                            System.out.printf("%.4g: =) (%.2f)\n", record.bandit.p, reward / pulls);
                            reinforcement = 1;
                        } else {
                            System.out.printf("%.4g: =( (%.2f)\n", record.bandit.p, reward / pulls);
                            reinforcement = reward / (reward - pulls);
                        }

                        activation.context.reinforce(Optional.empty(), Optional.empty(), reinforcement);
                        System.out.println(report());
                        newPulls.add(record);
                    }
                });
            }
        }

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
        public void close() {
            for (val record : bandits) {
                record.subscription.dispose();
            }
            kb.close();
        }

        public String report() {
            return bandits.stream().sorted((a, b) -> -Double.compare(a.bandit.p, b.bandit.p))
                    .map(record -> String.format("%.4g: %s", record.bandit.p,
                            record.node.synapse.getInputs().get(choose).getCoefficient()))
                    .collect(Collectors.joining("\n"));
        }
    }

    /**
     * Implements Bernoulli Bandit by plugging a minimal network into a tailored
     * test harness.
     */
    @Test
    public void testMinimalBinaryBandit() {
        try (val harness = new BinaryHarness(4)) {
            val best = harness.bandits.stream().max((a, b) -> Double.compare(a.bandit.p, b.bandit.p)).get();

            int consecutiveBest = 0;

            while (consecutiveBest < 100) {
                val pulls = harness.runTrial();
                if (pulls.size() == 1 && pulls.iterator().next() == best) {
                    ++consecutiveBest;
                } else {
                    consecutiveBest = 0;
                }

                System.out.println(harness.pulls);

                if (harness.pulls > 10000) {
                    fail(String.format("Did not converge after %d pulls with %.2f efficacy (%.2f).\n%s", harness.pulls,
                            harness.reward / harness.pulls / best.bandit.p, harness.reward / harness.pulls,
                            harness.report()));
                }
            }

            System.out.printf("Converged after around %d pulls with %.2f efficacy (%.2f).\n", harness.pulls,
                    harness.reward / harness.pulls / best.bandit.p, harness.reward / harness.pulls);
        }
    }
}
