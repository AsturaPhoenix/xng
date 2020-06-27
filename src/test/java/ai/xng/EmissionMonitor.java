package ai.xng;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import lombok.val;

public class EmissionMonitor<T> {
  private List<T> emissions = new ArrayList<>();

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

  public void emit(final T item) {
    emissions.add(item);
  }

  public static <T> EmissionMonitor<T> fromObservable(final Observable<T> source) {
    val monitor = new EmissionMonitor<T>();
    source.subscribe(monitor::emit);
    return monitor;
  }
}