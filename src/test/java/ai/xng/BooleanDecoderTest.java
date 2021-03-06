package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import ai.xng.constructs.BooleanDecoder;
import lombok.val;

public class BooleanDecoderTest {
  @Test
  public void testCoincident() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val executionCluster = new BiCluster();
    val inputCluster = new InputCluster();
    val actionCluster = new ActionCluster();
    val dataCluster = new DataCluster(inputCluster);
    val decoder = new BooleanDecoder(actionCluster, dataCluster, inputCluster,
        o -> Optional.of((boolean) o));

    val trigger = executionCluster.new Node();
    trigger.then(decoder.node, dataCluster.new FinalNode<>(true));
    trigger.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(inputCluster.activations()).startsWith(decoder.isTrue);
  }

  /**
   * Covers a bug where a node that had recently been activated could be decoded
   * twice.
   */
  @Test
  public void testRecentlyActivatedData() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val executionCluster = new BiCluster();
    val inputCluster = new InputCluster();
    val actionCluster = new ActionCluster();
    val dataCluster = new DataCluster(inputCluster);
    val decoder = new BooleanDecoder(actionCluster, dataCluster, inputCluster,
        o -> Optional.of((boolean) o));

    val data = dataCluster.new FinalNode<>(true);
    data.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

    val monitor = new EmissionMonitor<Long>();
    decoder.isTrue.getPosteriors()
        .getDistribution(TestUtil.testNode(actionCluster, monitor), IntegrationProfile.TRANSIENT).set(.8f);

    val trigger = executionCluster.new Node();
    // The order here actually seems to matter, along with a Heisenbug aspect where
    // if decoder.isTrue does not have posteriors, the opposite ordering is needed
    // to produce the double activation (which however is then not detectable).
    trigger.then(data, decoder.node);
    trigger.activate();
    scheduler.fastForwardUntilIdle();
    assertFalse(monitor.didEmit());
  }

  @Test
  public void testDataAfterWindow() {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;

    val executionCluster = new BiCluster();
    val inputCluster = new InputCluster();
    val actionCluster = new ActionCluster();
    val dataCluster = new DataCluster(inputCluster);
    val decoder = new BooleanDecoder(actionCluster, dataCluster, inputCluster,
        o -> Optional.of((boolean) o));

    val decoderTrigger = executionCluster.new Node();
    decoderTrigger.then(decoder.node);
    decoderTrigger.activate();
    scheduler.fastForwardFor(IntegrationProfile.TRANSIENT.period());

    val dataTrigger = executionCluster.new Node();
    dataTrigger.then(dataCluster.new FinalNode<>(true));
    dataTrigger.activate();
    scheduler.fastForwardUntilIdle();
    assertThat(inputCluster.activations()).isEmpty();
  }
}
