package ai.xng;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

import io.reactivex.disposables.Disposable;

public class DisposingWeakReference<T> extends WeakReference<T> {
    private static final ReferenceQueue<Object> queue = new ReferenceQueue<>();
    static {
        new Thread(() -> {
            try {
                while (!Thread.interrupted()) {
                    ((DisposingWeakReference<?>) queue.remove()).disposable.dispose();
                }
            } catch (InterruptedException e) {
            }
        }).start();
    }

    public Disposable disposable;

    public DisposingWeakReference(T referent) {
        super(referent, queue);
    }

    public DisposingWeakReference(T referent, Disposable disposable) {
        this(referent);
        this.disposable = disposable;
    }
}