package ai.xng;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.google.common.collect.Streams;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;

public class Node implements Serializable {
  private static final long serialVersionUID = 8798023148407057463L;

  /**
   * Margin by which to pad on either side of a threshold for default conjunctive
   * or disjunctive edges.
   */
  public static final float THRESHOLD_MARGIN = .2f;

  /**
   * Activation history TTL, in ms.
   */
  public static final long ACTIVATION_HISTORY = 15000;

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
    private final ArrayDeque<Long> activations = new ArrayDeque<>();
    private final Subject<Context.Ref> rxInput = PublishSubject.create();

    public ContextualState(final Context context, final RecencyQueue<Node>.Link activationListener) {
      rxInput.subscribe(ref -> {
        // This isn't critical here, but this establishes a frame of reference to aid
        // readability.
        assert context.getScheduler().isOnThread();

        // Update the activation timestamp early on as reinforcement calculations use it
        // for correlation. Accordingly, update the recency queue. However, note that
        // the posterior may activate with a later timestamp or not at all, depending on
        // onActivate. This should be fine because although still reflected in the
        // recency queue, the actual evaluations used for reinforcement calculations are
        // in the posterior synapse.
        final long now = context.getScheduler().now(TimeUnit.MILLISECONDS);
        final long oldestAllowed = now - ACTIVATION_HISTORY;
        while (!activations.isEmpty() && activations.getLast() < oldestAllowed) {
          activations.removeLast();
        }
        activations.addFirst(now);
        // Also, since Hebbian reinforcement windows look at timestamps in the recency
        // queue, it's better if we do maintain consistency between these timestamps and
        // the ordering in the queue.
        activationListener.promote();

        // If onActivate doesn't take us off the dispatch thread, prefer to update
        // timestamps immediately. Note that synapses will defer further propagation
        // themselves regardless.
        onActivate(context).observeOn(context.getScheduler().preferImmediate())
            // It's important to note that this holds onto the ref through the error
            // handler. Were we to release the ref before catch, we could close the context
            // prematurely when it's about to complete exceptionally.
            .doFinally(ref::close).subscribe(() -> {
              // continuation: Synapse.Profile::onActivate
              rxOutput.onNext(new Activation(context, context.getScheduler().now(TimeUnit.MILLISECONDS)));
            },
                // Caution, this may behave strangely if invocations happen against contexts
                // that have been deserialized since contexts are intended to be ephemeral and
                // overridden exception handlers will be lost.
                // TODO(rosswang): Maybe preserve the node that was actually activated.
                // TODO(rosswang): Do not allow activation against deserialized contexts.
                context.exceptionHandler);
      });
    }
  }

  @Getter
  private transient Object value;
  public String comment;
  public final Map<Node, Node> properties = Collections.synchronizedMap(new HashMap<>());

  private transient Subject<Activation> rxOutput;

  public Node() {
    this(null);
  }

  public Node(final Object value) {
    this.value = value;
    preInit();
  }

  private void preInit() {
    rxOutput = PublishSubject.create();
  }

  private void writeObject(ObjectOutputStream stream) throws IOException {
    stream.defaultWriteObject();

    if (value == null || value instanceof Serializable) {
      stream.writeObject(value);
    } else {
      // Just discard non-serializable values. It would be nice to warn here but there
      // are legitimate ephemeral values we can be storing in contexts at the time of
      // serialization.
      stream.writeObject(null);
    }
  }

  private void readObject(final ObjectInputStream stream) throws IOException, ClassNotFoundException {
    preInit();
    stream.defaultReadObject();
    value = stream.readObject();
  }

  public Stream<Long> getRecentActivations(final Context context, final boolean oldestFirst) {
    val activations = context.nodeState(this).activations;
    return oldestFirst ? Streams.stream(activations.descendingIterator()) : activations.stream();
  }

  public long getLastActivation(final Context context) {
    return getRecentActivations(context, false).findFirst().orElse(0L);
  }

  public void activate(final Context context) {
    val ref = context.new Ref();
    context.getScheduler().ensureOnThread(() -> context.nodeState(this).rxInput.onNext(ref));
  }

  /**
   * An optional handler that can block activation until a task has completed.
   */
  protected Completable onActivate(final Context context) {
    return Completable.complete();
  }

  public Observable<Activation> rxActivate() {
    return rxOutput;
  }

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

  public String debugDump(final Predicate<? super Map.Entry<Node, Node>> propertyFilter) {
    val out = new StringBuilder();
    debugDump(0, new HashSet<>(), out, propertyFilter);
    return out.toString();
  }

  public void debugDump() {
    System.out.println(debugDump(p -> true));
  }

  private static void indent(final int level, final StringBuilder out) {
    for (int i = 0; i < level; ++i) {
      out.append("  ");
    }
  }

  private void debugDump(final int indentLevel, final Set<Node> visited, final StringBuilder out,
      final Predicate<? super Map.Entry<Node, Node>> propertyFilter) {
    out.append(this);

    synchronized (properties) {
      val it = properties.entrySet().stream().filter(propertyFilter).iterator();
      if (it.hasNext()) {
        if (visited.contains(this)) {
          out.append(" { ... }");
        } else {
          visited.add(this);
          out.append(" {\n");
          final int childIndent = indentLevel + 1;
          while (it.hasNext()) {
            val property = it.next();
            indent(childIndent, out);
            out.append(property.getKey());
            out.append(" => ");
            property.getValue().debugDump(childIndent, visited, out, propertyFilter);
            if (it.hasNext()) {
              out.append(",\n");
            } else {
              out.append('\n');
            }
          }
          indent(indentLevel, out);
          out.append('}');
        }
      }
    }
  }

  /**
   * @return next
   */
  public Node then(final SynapticNode next) {
    next.getSynapse().setCoefficient(this, Synapse.THRESHOLD + THRESHOLD_MARGIN);
    return next;
  }
}
