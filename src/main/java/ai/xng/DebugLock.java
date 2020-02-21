package ai.xng;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

public class DebugLock implements AutoCloseable {
    public static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final Lock lock;

    public DebugLock(final Lock lock) {
        this.lock = lock;
        try {
            if (lock.tryLock(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.err.println("Unable to acquire lock in " + TIMEOUT);
        Thread.dumpStack();

        lock.lock();
    }

    public void close() {
        lock.unlock();
    }
}
