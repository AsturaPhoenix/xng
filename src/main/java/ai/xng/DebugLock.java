package ai.xng;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import com.google.common.collect.Lists;

import lombok.val;

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

    public static class Multiple implements AutoCloseable {
        final ArrayList<DebugLock> locked;

        public Multiple(final Lock... locks) {
            locked = new ArrayList<>(locks.length);

            try {
                for (val lock : locks) {
                    locked.add(new DebugLock(lock));
                }
            } catch (final RuntimeException e) {
                close();
                throw e;
            }
        }

        @Override
        public void close() {
            for (val lock : Lists.reverse(locked)) {
                lock.close();
            }
        }
    }
}
