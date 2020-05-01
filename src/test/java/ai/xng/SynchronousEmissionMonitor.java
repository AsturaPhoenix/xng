package ai.xng;

import io.reactivex.Observable;

public class SynchronousEmissionMonitor<T> {
    private boolean didEmit;

    public SynchronousEmissionMonitor(final Observable<T> source) {
        source.subscribe(__ -> didEmit = true);
    }

    public void reset() {
        didEmit = false;
    }

    public boolean didEmit() {
        final boolean didEmit = this.didEmit;
        reset();
        return didEmit;
    }
}