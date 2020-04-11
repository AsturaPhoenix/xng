package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.val;

public class Node implements Serializable {
  private static final long serialVersionUID = -4340465118968553513L;

  @FunctionalInterface
  public static interface OnActivate {
    Completable run(Context context);
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
      rxInput.subscribe(ref -> {
        // This isn't critical here, but this establishes a frame of reference to aid
        // readability.
        assert context.getScheduler().isOnThread();

        final Completable completion = onActivate == null ? Completable.complete() : onActivate.run(context);

        completion.observeOn(context.getScheduler())
            // It's important to note that this holds onto the ref through the error
            // handler. Were we to release the ref before catch, we could close the context
            // prematurely when it's about to complete exceptionally.
            .doFinally(ref::close).subscribe(() -> {
              lastActivation = context.getScheduler().now(TimeUnit.MILLISECONDS);
              // continuation: Synapse.Profile::onActivate
              rxOutput.onNext(new Activation(context, lastActivation));
            },
                // Caution, this may behave strangely if invocations happen against contexts
                // that have been deserialized since contexts are intended to be ephemeral and
                // overridden exception handlers will be lost.
                // TODO(rosswang): preserve nodespace stack trace
                // TODO(rosswang): do not allow activation against deserialized contexts
                context.exceptionHandler);
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
    val ref = context.new Ref();
    context.getScheduler().ensureOnThread(() -> context.nodeState(this).rxInput.onNext(ref));
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
