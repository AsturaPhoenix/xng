package io.tqi.ekg;

import io.reactivex.Observable;

public interface ChangeObservable<T> {
    Observable<T> rxChange();
}
