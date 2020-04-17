package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
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

        // If onActivate doesn't take us off the dispatch thread, prefer to update
        // timestamps immediately. Note that synapses will defer further propagation
        // themselves regardless.
        onActivate(context).observeOn(context.getScheduler().preferImmediate())
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

  private transient Subject<Activation> rxOutput;

  public Node() {
    this(null);
  }

  public Node(final Serializable value) {
    this.value = value;
    init();
  }

  private void init() {
    rxOutput = PublishSubject.create();
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    init();
    stream.defaultReadObject();
  }

  public void activate(final Context context) {
    val ref = context.new Ref();
    context.getScheduler().ensureOnThread(() -> context.nodeState(this).rxInput.onNext(ref));
  }

  /**
   * An optional handler that
   */
  protected Completable onActivate(final Context context) {
    return Completable.complete();
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

  /**
   * @return next
   */
  public Node then(final SynapticNode next) {
    next.synapse.setCoefficient(this, 1);
    return next;
  }
}
