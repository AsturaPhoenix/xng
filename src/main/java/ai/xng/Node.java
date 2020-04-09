package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.val;

public class Node implements Serializable {
  private static final long serialVersionUID = -4340465118968553513L;

  public static interface OnActivate {
    void run(Context context) throws Exception;
  }

  @RequiredArgsConstructor
  @EqualsAndHashCode
  public static class Activation {
    public final Context context;
    public final long timestamp;
  }

  public class ContextualState {
    private long lastActivation;
    private Subject<Context.Ref> rxInput = PublishSubject.create();

    public ContextualState(final Context context) {
      rxInput.observeOn(Schedulers.io()).subscribe(ref -> {
        try {
          if (onActivate != null)
            onActivate.run(context);

          try (val lock = new DebugLock(context.mutex())) {
            long newTimestamp = System.currentTimeMillis();
            lastActivation = newTimestamp;
            // continuation: Synapse.Profile::onActivate
            rxOutput.onNext(new Activation(context, newTimestamp));
          }
        } catch (final RuntimeException e) {
          // Caution, this may behave strangely if invocations happen against contexts
          // that have been deserialized since contexts are intended to be ephemeral and
          // overridden exception handlers will be lost.
          // TODO(rosswang): preserve nodespace stack trace
          context.exceptionHandler.accept(e);
        } finally {
          // This differs from try-with-resources not only in that the resource is passed
          // in rather than allocated, but also in that it is held until after the catch
          // block. This is equivalent to surrounding the try-catch with a
          // try-with-resources that copies the ref, but is a bit cleaner.
          //
          // Were we to release the ref before catch, we could close the context
          // prematurely when it's about to complete exceptionally.
          ref.close();
        }
      });
    }
  }

  public final Serializable value;

  public long getLastActivation(final Context context) {
    final ContextualState state = context.nodeState(this);
    return state == null ? 0 : state.lastActivation;
  }

  @Getter
  private final Synapse synapse;

  // TODO(rosswang): Can we get rid of onActivate and only use rxActivate instead?
  @Setter
  private transient OnActivate onActivate;
  private transient Subject<Activation> rxOutput;

  public Node() {
    this(null);
  }

  public Node(final Serializable value) {
    this(value, true);
  }

  public Node(final Serializable value, final boolean hasSynapse) {
    this.value = value;
    preInit();

    synapse = hasSynapse ? new Synapse() : null;

    postInit();
  }

  private void preInit() {
    rxOutput = PublishSubject.create();
  }

  private void postInit() {
    if (synapse != null)
      synapse.rxActivate().subscribe(a -> activate(a.context));
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    preInit();
    stream.defaultReadObject();
    postInit();
  }

  public void activate(final Context context) {
    try (val lock = new DebugLock(context.mutex())) {
      context.nodeState(this).rxInput.onNext(context.new Ref());
    }
  }

  public Observable<Activation> rxActivate() {
    return rxOutput;
  }

  public String comment;
  public final Map<Node, Node> properties = Collections.synchronizedMap(new HashMap<>());

  @Override
  public String toString() {
    val sb = new StringBuilder(Integer.toHexString(hashCode()));
    if (comment != null) {
      sb.append(": ").append(comment);
    }
    if (value != null) {
      sb.append(" = ").append(value);
    }
    return sb.toString();
  }

  public String displayString() {
    return Objects.toString(value);
  }

  /**
   * @return next
   */
  public Node then(final Node next) {
    next.synapse.setCoefficient(this, 1);
    return next;
  }
}
