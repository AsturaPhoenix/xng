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

  private static final long DEFAULT_REFRACTORY = 10;

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
    private Subject<Long> rxInput = PublishSubject.create();

    public ContextualState(final Context context) {
      rxInput.observeOn(Schedulers.io()).subscribe(t -> {
        try {
          if (t - lastActivation >= refractory) {
            if (onActivate != null)
              onActivate.run(context);
            synchronized (this) {
              long newTimestamp = System.currentTimeMillis();
              lastActivation = newTimestamp;
              // continuation: Synapse.Profile::onActivate
              rxOutput.onNext(new Activation(context, newTimestamp));
            }
          }
        } catch (final Exception e) {
          // Caution, this may behave strangely if invocations happen against contexts
          // that have been deserialized since contexts are intended to be ephemeral and
          // overridden exception handlers will be lost.
          // TODO(rosswang): preserve nodespace stack trace
          context.exceptionHandler.accept(e);
        } finally {
          // addRef: Node::activate
          context.releaseRef();
        }
      });
    }
  }

  @Getter
  private final Serializable value;

  public long getLastActivation(final Context context) {
    final ContextualState state = context.nodeState(this);
    return state == null ? 0 : state.lastActivation;
  }

  public final Synapse synapse = new Synapse();

  @Getter
  @Setter
  private long refractory = DEFAULT_REFRACTORY;

  // TODO(rosswang): Can we get rid of onActivate and only use rxActivate instead?
  @Setter
  private transient OnActivate onActivate;
  private transient Subject<Activation> rxOutput;

  @Getter
  @Setter
  private boolean pinned;

  public Node() {
    this(null);
  }

  public Node(final Serializable value) {
    this.value = value;
    preInit();
    postInit();
  }

  public static final int ACTIVATION_HISTORY = 5;

  private void preInit() {
    rxOutput = PublishSubject.create();
  }

  private void postInit() {
    synapse.rxActivate().subscribe(a -> activate(a.context));
  }

  private void readObject(final ObjectInputStream stream) throws ClassNotFoundException, IOException {
    preInit();
    stream.defaultReadObject();
    postInit();
  }

  public void activate(final Context context) {
    // releaseRef: ContextualState::ContextualState
    context.addRef();
    context.nodeState(this).rxInput.onNext(System.currentTimeMillis());
  }

  public Observable<Activation> rxActivate() {
    return rxOutput;
  }

  public final Map<Node, Node> properties = Collections.synchronizedMap(new HashMap<>());

  @Override
  public String toString() {
    val sb = new StringBuilder(Integer.toHexString(hashCode()));
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
