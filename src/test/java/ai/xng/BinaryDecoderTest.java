package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import ai.xng.constructs.BinaryDecoder;
import lombok.val;

public class BinaryDecoderTest {
  @Test
  public void testNone() {
    val output = new InputCluster();
    val decoder = new BinaryDecoder(() -> 0, output);
    decoder.activate();
    assertThat(output.activations()).containsExactlyInAnyOrder(
        decoder.output.get(0).lo(),
        decoder.output.get(1).lo(),
        decoder.output.get(2).lo(),
        decoder.output.get(3).lo(),
        decoder.output.get(4).lo(),
        decoder.output.get(5).lo(),
        decoder.output.get(6).lo(),
        decoder.output.get(7).lo(),
        decoder.output.get(8).lo(),
        decoder.output.get(9).lo(),
        decoder.output.get(10).lo(),
        decoder.output.get(11).lo(),
        decoder.output.get(12).lo(),
        decoder.output.get(13).lo(),
        decoder.output.get(14).lo(),
        decoder.output.get(15).lo(),
        decoder.output.get(16).lo(),
        decoder.output.get(17).lo(),
        decoder.output.get(18).lo(),
        decoder.output.get(19).lo(),
        decoder.output.get(20).lo());
  }

  @Test
  public void testAll() {
    val output = new InputCluster();
    val decoder = new BinaryDecoder(() -> 0x1FFFFF, output);
    decoder.activate();
    assertThat(output.activations()).containsExactlyInAnyOrder(
        decoder.output.get(0).hi(),
        decoder.output.get(1).hi(),
        decoder.output.get(2).hi(),
        decoder.output.get(3).hi(),
        decoder.output.get(4).hi(),
        decoder.output.get(5).hi(),
        decoder.output.get(6).hi(),
        decoder.output.get(7).hi(),
        decoder.output.get(8).hi(),
        decoder.output.get(9).hi(),
        decoder.output.get(10).hi(),
        decoder.output.get(11).hi(),
        decoder.output.get(12).hi(),
        decoder.output.get(13).hi(),
        decoder.output.get(14).hi(),
        decoder.output.get(15).hi(),
        decoder.output.get(16).hi(),
        decoder.output.get(17).hi(),
        decoder.output.get(18).hi(),
        decoder.output.get(19).hi(),
        decoder.output.get(20).hi());
  }

  @Test
  public void testFish() {
    val output = new InputCluster();
    val decoder = new BinaryDecoder(() -> 42, output);
    decoder.activate();
    assertThat(output.activations()).containsExactlyInAnyOrder(
        decoder.output.get(0).lo(),
        decoder.output.get(1).hi(),
        decoder.output.get(2).lo(),
        decoder.output.get(3).hi(),
        decoder.output.get(4).lo(),
        decoder.output.get(5).hi(),
        decoder.output.get(6).lo(),
        decoder.output.get(7).lo(),
        decoder.output.get(8).lo(),
        decoder.output.get(9).lo(),
        decoder.output.get(10).lo(),
        decoder.output.get(11).lo(),
        decoder.output.get(12).lo(),
        decoder.output.get(13).lo(),
        decoder.output.get(14).lo(),
        decoder.output.get(15).lo(),
        decoder.output.get(16).lo(),
        decoder.output.get(17).lo(),
        decoder.output.get(18).lo(),
        decoder.output.get(19).lo(),
        decoder.output.get(20).lo());
  }

  @Test
  public void testSerialization() throws Exception {
    val output = new InputCluster();
    TestUtil.serialize(new BinaryDecoder(() -> 42, output));
  }
}
