package ai.xng;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import lombok.val;

public class BinaryDecoderTest {
  @Test
  public void testNone() {
    val output = new InputCluster();
    val decoder = new BinaryDecoder(new TestDataNode(0), output);
    decoder.activate();
    assertThat(output.activations()).containsExactlyInAnyOrder(
        decoder.output.get(0x0).lo(),
        decoder.output.get(0x1).lo(),
        decoder.output.get(0x2).lo(),
        decoder.output.get(0x3).lo(),
        decoder.output.get(0x4).lo(),
        decoder.output.get(0x5).lo(),
        decoder.output.get(0x6).lo(),
        decoder.output.get(0x7).lo(),
        decoder.output.get(0x8).lo(),
        decoder.output.get(0x9).lo(),
        decoder.output.get(0xa).lo(),
        decoder.output.get(0xb).lo(),
        decoder.output.get(0xc).lo(),
        decoder.output.get(0xd).lo(),
        decoder.output.get(0xe).lo(),
        decoder.output.get(0xf).lo());
  }

  @Test
  public void testAll() {
    val output = new InputCluster();
    val decoder = new BinaryDecoder(new TestDataNode(0xFFFF), output);
    decoder.activate();
    assertThat(output.activations()).containsExactlyInAnyOrder(
        decoder.output.get(0x0).hi(),
        decoder.output.get(0x1).hi(),
        decoder.output.get(0x2).hi(),
        decoder.output.get(0x3).hi(),
        decoder.output.get(0x4).hi(),
        decoder.output.get(0x5).hi(),
        decoder.output.get(0x6).hi(),
        decoder.output.get(0x7).hi(),
        decoder.output.get(0x8).hi(),
        decoder.output.get(0x9).hi(),
        decoder.output.get(0xa).hi(),
        decoder.output.get(0xb).hi(),
        decoder.output.get(0xc).hi(),
        decoder.output.get(0xd).hi(),
        decoder.output.get(0xe).hi(),
        decoder.output.get(0xf).hi());
  }

  @Test
  public void testFish() {
    val output = new InputCluster();
    val decoder = new BinaryDecoder(new TestDataNode(42), output);
    decoder.activate();
    assertThat(output.activations()).containsExactlyInAnyOrder(
        decoder.output.get(0x0).lo(),
        decoder.output.get(0x1).hi(),
        decoder.output.get(0x2).lo(),
        decoder.output.get(0x3).hi(),
        decoder.output.get(0x4).lo(),
        decoder.output.get(0x5).hi(),
        decoder.output.get(0x6).lo(),
        decoder.output.get(0x7).lo(),
        decoder.output.get(0x8).lo(),
        decoder.output.get(0x9).lo(),
        decoder.output.get(0xa).lo(),
        decoder.output.get(0xb).lo(),
        decoder.output.get(0xc).lo(),
        decoder.output.get(0xd).lo(),
        decoder.output.get(0xe).lo(),
        decoder.output.get(0xf).lo());
  }

  @Test
  public void testSerialization() throws Exception {
    val output = new InputCluster();
    TestUtil.serialize(new BinaryDecoder(new TestDataNode(), output));
  }
}
