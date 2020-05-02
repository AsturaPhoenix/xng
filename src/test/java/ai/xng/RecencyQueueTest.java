package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import lombok.val;

public class RecencyQueueTest {
  @Test
  public void testEmpty() {
    assertThat(new RecencyQueue<>()).isEmpty();
  }

  @Test
  public void testUnpromoted() {
    val queue = new RecencyQueue<Integer>();
    queue.new Link(0);
    assertThat(queue).isEmpty();
  }

  @Test
  public void testThree() {
    val queue = new RecencyQueue<Integer>();
    queue.new Link(1).promote();
    queue.new Link(2).promote();
    queue.new Link(3).promote();
    assertThat(queue).containsExactly(3, 2, 1);
  }

  @Test
  public void testSerialization() throws Exception {
    val queue = new RecencyQueue<Integer>();
    queue.new Link(1).promote();
    queue.new Link(2).promote();
    queue.new Link(3).promote();
    assertThat(queue).containsExactly(3, 2, 1);
  }

  @Test
  public void testPromoteTail() {
    val queue = new RecencyQueue<Integer>();
    val tail = queue.new Link(1);
    tail.promote();
    queue.new Link(2).promote();
    queue.new Link(3).promote();
    tail.promote();
    assertThat(queue).containsExactly(1, 3, 2);
  }

  @Test
  public void testPromoteHead() {
    val queue = new RecencyQueue<Integer>();
    queue.new Link(1).promote();
    queue.new Link(2).promote();
    val head = queue.new Link(3);
    head.promote();
    head.promote();
    assertThat(queue).containsExactly(3, 2, 1);
  }

  @Test
  public void testPromoteMid() {
    val queue = new RecencyQueue<Integer>();
    queue.new Link(1).promote();
    val mid = queue.new Link(2);
    mid.promote();
    queue.new Link(3).promote();
    mid.promote();
    assertThat(queue).containsExactly(2, 3, 1);
  }

  @Test
  public void testSwap() {
    val queue = new RecencyQueue<Integer>();
    val tail = queue.new Link(1);
    tail.promote();
    queue.new Link(2).promote();
    tail.promote();
    assertThat(queue).containsExactly(1, 2);
  }
}