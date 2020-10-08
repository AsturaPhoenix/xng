package ai.xng;

import org.junit.jupiter.api.Test;

import lombok.val;

import static org.assertj.core.api.Assertions.assertThat;

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
    val a = cluster.new Node(), b = cluster.new Node(), c = cluster.new Node();
    a.activate();
    b.activate();
    c.activate();
    assertThat(cluster.activations()).containsExactly(c, b, a);
  }
}
