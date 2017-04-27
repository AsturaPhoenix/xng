package io.tqi.asn;

import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;

public class EmissionMonitor<T> {
	private static final Object DID_NOT_ACTIVATE = new Object();

	private Observable<? extends T> source;
	private Observable<? extends T> monitor;

	public EmissionMonitor(final Observable<? extends T> source) {
		reset(source);
	}

	public void reset(final Observable<? extends T> source) {
		this.source = source;
		reset();
	}

	public void reset() {
		monitor = source.replay().autoConnect(0);
	}

	public Observable<? extends T> emissions() {
		return monitor.takeUntil(Observable.timer(50, TimeUnit.MILLISECONDS));
	}

	boolean didEmit() {
		boolean didEmit = emissions().<Object>map(x -> this).blockingFirst(DID_NOT_ACTIVATE) != DID_NOT_ACTIVATE;
		reset();
		return didEmit;
	}
}