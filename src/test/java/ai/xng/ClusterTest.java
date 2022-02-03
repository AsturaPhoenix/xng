package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Serializable;

import org.junit.jupiter.api.Test;

import lombok.val;

public class ClusterTest {
  @Test
  public void testActivations0() {
    val cluster = new InputCluster();
    cluster.new Node();
    assertThat(cluster.activations()).isEmpty();
  }

  @Test
  public void testActivations3() {
    val cluster = new InputCluster();

    val a = cluster.new Node();
    val b = cluster.new Node();
    val c = cluster.new Node();

    a.activate();
    b.activate();
    c.activate();
    assertThat(cluster.activations()).containsExactly(c, b, a);
  }

  private static class SingleNodeCluster implements Serializable {
    final InputCluster cluster = new InputCluster();
    final InputCluster.Node node = cluster.new Node();
  }

  @Test
  public void testActivationsClearedAfterDeserialization() throws Exception {
    val original = new SingleNodeCluster();
    original.node.activate();

    val deserialized = TestUtil.serialize(original);
    assertThat(original.cluster.activations()).containsExactly(original.node);
    assertThat(deserialized.cluster.activations()).isEmpty();

    deserialized.node.activate();
    assertThat(deserialized.cluster.activations()).containsExactly(deserialized.node);
  }

  private static class GcTestClusters implements Serializable {
    final InputCluster input = new InputCluster();
    final BiCluster intermediate = new BiCluster();
    final ActionCluster output = new ActionCluster();
  }

  /**
   * Clusters should not hold strong references to anonymous nodes since if the
   * prior cannot be activated, the chain should not have any effect.
   */
  @Test
  public void testGc() throws Exception {
    val scheduler = new TestScheduler();
    Scheduler.global = scheduler;
    val clusters = new GcTestClusters();

    val gc = new GcFixture(clusters);

    for (int i = 0; i < 1000; ++i) {
      val input = clusters.input.new Node();
      input.then(clusters.intermediate.new Node())
          .then(clusters.output.new Node(() -> {
          }));
      input.activate();
    }

    scheduler.fastForwardUntilIdle();

    gc.assertNoGrowth(() -> {
      System.gc();
      clusters.input.clean();
      clusters.intermediate.clean();
      clusters.output.clean();
    });
  }
}
