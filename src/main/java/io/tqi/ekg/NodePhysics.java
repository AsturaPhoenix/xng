package io.tqi.ekg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multiset;

import io.reactivex.Observable;
import io.tqi.ekg.Synapse.Profile;
import javafx.geometry.Point3D;

public class NodePhysics {
    private static class Link {
        Link next;
        Set<Link> bucket;
        final Node.Ref node;

        Link(final Node node) {
            this.node = node.ref();
        }
    }

    private final Object $lock = new Object();
    private Link head;

    private static class Counters {
        final Multiset<Node> propUsage = HashMultiset.create();
        final Map<Node, Double> assocUsage = new HashMap<>();

        void clear() {
            propUsage.clear();
            assocUsage.clear();
        }

        void addAssoc(final Node a, final Node b, final double coefficient) {
            addAssoc(a, coefficient);
            addAssoc(b, coefficient);
        }

        void addAssoc(final Node node, final double coefficient) {
            assocUsage.merge(node, coefficient, (a, b) -> a + b);
        }
    }

    private Counters counters = new Counters(), nextCounters = new Counters();

    public NodePhysics() {
        Observable.interval(1000 / 60, TimeUnit.MILLISECONDS).subscribe(f -> process());
    }

    public void add(final Node node) {
        final Link link = new Link(node);
        synchronized ($lock) {
            link.next = head;
            head = link;
        }
    }

    public void addAll(final Collection<Node> nodes) {
        for (final Node node : nodes) {
            add(node);
        }
    }

    private void process() {
        Link link;
        synchronized ($lock) {
            while (head != null && head.node.get() == null) {
                head = head.next;
            }
            link = head;
        }

        while (link != null) {
            final Node node = link.node.get();
            if (node != null) {
                updateBucket(link);
                process(node);
            }

            while (link.next != null && link.next.node.get() == null) {
                link.next = link.next.next;
            }
            link = link.next;
        }

        final Counters swap = counters;
        counters = nextCounters;
        swap.clear();
        nextCounters = swap;
    }

    private static final int GRID_SIZE = 1;
    private final Map<Integer, Map<Integer, Map<Integer, Set<Link>>>> grid = new HashMap<>();

    private Iterable<Link> getNeighborhood(final Node node) {
        final Point3D pt = node.getLocation();
        final int x = (int) (pt.getX() / GRID_SIZE), y = (int) (pt.getY() / GRID_SIZE),
                z = (int) (pt.getZ() / GRID_SIZE);

        final Collection<Iterable<Link>> buckets = new ArrayList<>(9);
        for (int nx = x - 1; nx <= x + 1; nx++) {
            final Map<Integer, Map<Integer, Set<Link>>> mx = grid.get(nx);
            if (mx == null)
                continue;
            for (int ny = y - 1; ny <= y + 1; ny++) {
                final Map<Integer, Set<Link>> my = mx.get(ny);
                if (my == null)
                    continue;
                for (int nz = z - 1; nz <= z + 1; nz++) {
                    final Set<Link> mz = my.get(nz);
                    if (mz != null)
                        buckets.add(mz);
                }
            }
        }

        return Iterables.concat(buckets);
    }

    private void updateBucket(final Link link) {
        final Set<Link> bucket;
        final Node node = link.node.get();
        if (node == null)
            return;

        final Point3D pt = node.getLocation();
        if (pt == null)
            return;

        final int x = (int) (pt.getX() / GRID_SIZE), y = (int) (pt.getY() / GRID_SIZE),
                z = (int) (pt.getZ() / GRID_SIZE);
        bucket = grid.computeIfAbsent(x, w -> new HashMap<>()).computeIfAbsent(y, w -> new HashMap<>())
                .computeIfAbsent(z, w -> new HashSet<>());

        if (link.bucket != bucket) {
            if (link.bucket != null)
                link.bucket.remove(link);
            bucket.add(link);
            link.bucket = bucket;
        }
    }

    private void attract(final Node a, final Node b, final double k, final double zx) {
        if (a.getLocation() == null) {
            a.setLocation(b.getLocation().add(.5, .5, 0));
        }

        final Point3D vec = b.getLocation().subtract(a.getLocation());
        final double dist = vec.magnitude();
        final Point3D delta = vec.normalize().multiply(Math.min(k * Math.max(dist - zx, 0), .2) / 2);

        if (delta.dotProduct(delta) > .00001) {
            if (!a.isPinned()) {
                a.setLocation(a.getLocation().add(delta));
            }

            if (!b.isPinned()) {
                b.setLocation(b.getLocation().subtract(delta));
            }
        }
    }

    private void attract(final Node a, final Node b1, final Node b2, final double k, final double zx) {
        if (a.getLocation() == null) {
            a.setLocation(b1.getLocation().midpoint(b2.getLocation()).add(0, 1, 0));
        }

        final Point3D vec = a.getLocation().subtract(b1.getLocation().midpoint(b2.getLocation()));
        final double dist = vec.magnitude();
        final Point3D delta = vec.normalize().multiply(Math.min(k * Math.max(dist - zx, 0), .2) / 3);

        if (delta.dotProduct(delta) > .00001) {
            if (!a.isPinned()) {
                a.setLocation(a.getLocation().subtract(delta));
            }

            if (!b1.isPinned()) {
                b1.setLocation(b1.getLocation().add(delta));
            }

            if (!b2.isPinned()) {
                b2.setLocation(b2.getLocation().add(delta));
            }
        }
    }

    private void process(final Node node) {
        if (!node.properties().isEmpty() && node.getLocation() == null) {
            node.setLocation(Point3D.ZERO);
        }

        final double myLoosening = Math.max(1, counters.assocUsage.getOrDefault(node, 1.0) - 4);

        for (final Entry<Node, Profile> assoc : node.getSynapse()) {
            final float k = Math.abs(assoc.getValue().getCoefficient());
            nextCounters.addAssoc(node, assoc.getKey(), k);
            final double loosening = (myLoosening
                    + Math.max(1, counters.assocUsage.getOrDefault(assoc.getKey(), 1.0) - 4)) / 2;
            attract(assoc.getKey(), node, .1 * k / loosening, 1.2 * loosening);
        }

        final int nProps = node.properties().size();

        synchronized (node.properties().mutex()) {
            for (final Entry<Node, Node> prop : node.properties().entrySet()) {
                if (prop.getValue() == null)
                    continue;

                nextCounters.propUsage.add(prop.getKey());

                attract(prop.getValue(), node, .05 / nProps, 1.2 * nProps);
                final int nUses = counters.propUsage.count(prop.getKey());
                attract(prop.getKey(), node, prop.getValue(), .02 / nUses, 2 * nUses);
            }
        }

        if (!node.isPinned() && node.getLocation() != null) {
            for (final Link otherLink : getNeighborhood(node)) {
                final Node other = otherLink.node.get();
                if (other == null || other == node)
                    continue;
                Point3D vec = node.getLocation().subtract(other.getLocation());
                if (vec.equals(Point3D.ZERO)) {
                    vec = new Point3D(.5 - Math.random(), .5 - Math.random(), .5 - Math.random());
                }

                final double dist = vec.magnitude();
                if (dist < GRID_SIZE - .001) {
                    node.setLocation(node.getLocation().add(vec.normalize().multiply((GRID_SIZE - dist) * .1)));
                }
            }
        }
    }
}
