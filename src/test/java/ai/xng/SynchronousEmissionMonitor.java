package ai.xng;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import lombok.val;

public class SynchronousEmissionMonitor<T> {
    private List<T> emissions = new ArrayList<>();

    public SynchronousEmissionMonitor(final Observable<T> source) {
        source.subscribe(e -> emissions.add(e)); // not a tear-off so we can hot swap emissions
    }

    public void reset() {
        emissions.clear();
    }

    public boolean didEmit() {
        final boolean didEmit = !emissions.isEmpty();
        reset();
        return didEmit;
    }

    public List<T> emissions() {
        val oldEmissions = emissions;
        emissions = new ArrayList<>();
        return oldEmissions;
    }
}