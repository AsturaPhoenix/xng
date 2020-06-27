package ai.xng;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.Serializable;

public class GcFixture {
  private static final long GC_PAUSE_MS = 1000;
  private static final int GC_ITERATIONS = 10;

  private final Serializable object;
  public final int initialSize;

  public GcFixture(final Serializable object) throws IOException {
    this.object = object;
    initialSize = TestUtil.getSerializedSize(object);
  }

  public void assertNoGrowth() throws IOException, InterruptedException {
    assertNoGrowth(System::gc);
  }

  public void assertNoGrowth(final Runnable gc) throws IOException, InterruptedException {
    assertSize("%d", initialSize, gc);
  }

  public void assertSize(final String format, final int limit) throws IOException, InterruptedException {
    assertSize(format, limit, System::gc);
  }

  public void assertSize(final String format, final int limit, final Runnable gc)
      throws IOException, InterruptedException {
    int finalSize = 0;
    for (int i = 0; i < GC_ITERATIONS; ++i) {
      if (Thread.interrupted())
        throw new InterruptedException();

      gc.run();
      Thread.sleep(GC_PAUSE_MS);
      finalSize = TestUtil.getSerializedSize(object);
      if (finalSize <= limit)
        return;
    }

    fail(String.format("%d > " + format, finalSize, initialSize));
  }
}
