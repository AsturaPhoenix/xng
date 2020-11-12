package ai.xng.constructs;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import ai.xng.ActionNode;
import ai.xng.Cluster;
import ai.xng.InputCluster;
import ai.xng.Scheduler;
import io.reactivex.Observable;

/**
 * This is a construct that, once activated, waits until the cluster(s) it is
 * monitoring go idle for the specified period. This is roughly equivalent to
 * kicking off a timing loop with a posterior that is inhibited by all nodes in
 * the clusters being watched.
 */
public class IdleWatcher implements ActionNode.Action {
  private static final long serialVersionUID = 1L;

  public final long period;
  public final InputCluster.Node output;
  public final ImmutableSet<Cluster<?>> clusters;

  public IdleWatcher(final long period, final InputCluster output, final Cluster<?>... clusters) {
    this(period, output, Arrays.asList(clusters));
  }

  public IdleWatcher(final long period, final InputCluster output, final Iterable<Cluster<?>> clusters) {
    this.clusters = ImmutableSet.copyOf(clusters);
    this.output = output.new Node();
    this.period = period;
  }

  @Override
  public void activate() {
    Observable.merge(Iterables.transform(clusters, Cluster::rxActivations))
        .timeout(period, TimeUnit.MILLISECONDS, Scheduler.global.getRx())
        .ignoreElements()
        .subscribe(() -> {
        }, t -> output.activate());
  }
}
