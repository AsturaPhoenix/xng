package io.tqi.ekg;

import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;

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
        return monitor.takeUntil(Observable.timer(250, TimeUnit.MILLISECONDS));
    }

    boolean didEmit() {
        boolean didEmit = emissions().cast(Object.class).blockingFirst(DID_NOT_ACTIVATE) != DID_NOT_ACTIVATE;
        reset();
        return didEmit;
    }
}