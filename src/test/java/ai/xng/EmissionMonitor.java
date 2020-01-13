package ai.xng;

import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;

public class EmissionMonitor<T> {
    private static final Object DID_NOT_ACTIVATE = new Object();

    private Observable<T> source;
    private Observable<T> monitor;

    public EmissionMonitor(final Observable<T> source) {
        reset(source);
    }

    public void reset(final Observable<T> source) {
        this.source = source;
        reset();
    }

    public void reset() {
        monitor = source.replay().autoConnect(0);
    }

    public Observable<T> emissions() {
        // Using the computation scheduler for monitoring can lead to deadlocks
        // when tests artificially block computation threads.
        return monitor.take(250, TimeUnit.MILLISECONDS, Schedulers.io());
    }

    public boolean didEmit() {
        boolean didEmit = emissions().cast(Object.class).blockingFirst(DID_NOT_ACTIVATE) != DID_NOT_ACTIVATE;
        reset();
        return didEmit;
    }
}