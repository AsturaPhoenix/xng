package ai.xng;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.Serializable;

public class GcFixture {
  private static final long GC_TIMEOUT_MS = 1000;

  private final Serializable object;
  public final int initialSize;

  public GcFixture(final Serializable object) throws IOException {
    this.object = object;
    initialSize = TestUtil.getSerializedSize(object);
  }

  public void assertNoGrowth() throws IOException, InterruptedException {
    assertSize("%d", initialSize);
  }

  public void assertSize(final String format, final int limit) throws IOException, InterruptedException {
    assertSize(format, limit, System::gc);
  }

  public void assertSize(final String format, final int limit, final Runnable gc)
      throws IOException, InterruptedException {
    final long deadline = System.currentTimeMillis() + GC_TIMEOUT_MS;
    int finalSize;
    do {
      if (Thread.interrupted())
        throw new InterruptedException();

      gc.run();
      finalSize = TestUtil.getSerializedSize(object);
      if (finalSize <= limit)
        return;

    } while (System.currentTimeMillis() < deadline);

    fail(String.format("%d > " + format, finalSize, initialSize));
  }
}
