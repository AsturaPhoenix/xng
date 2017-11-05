package io.tqi.ekg;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Iterables;

import io.reactivex.Observable;
import io.tqi.ekg.Synapse.Activation;
import javafx.geometry.Point3D;

public class NodePhysics {
    private static class Link extends WeakReference<Node> {
        Link next;
        Set<Link> bucket;

        Link(final Node node) {
            super(node);
        }
    }

    private final Object $lock = new Object();
    private Link head;

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
            while (head != null && head.get() == null) {
                head = head.next;
            }
            link = head;
        }

        while (link != null) {
            final Node node = link.get();
            if (node != null) {
                updateBucket(link);
                process(node);
            }

            while (link.next != null && link.next.get() == null) {
                link.next = link.next.next;
            }
            link = link.next;
        }
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
        final Node node = link.get();
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

    private void attract(final Node mobile, final Node stable, final double k) {
        final Point3D vec = stable.getLocation().subtract(mobile.getLocation());
        final double dist = vec.magnitude();
        final Point3D delta = vec.normalize().multiply(Math.min(k * Math.max(dist - 1.5, 0), .2));

        if (!mobile.isPinned()) {
            mobile.setLocation(mobile.getLocation().add(delta));
        } else if (!stable.isPinned()) {
            stable.setLocation(stable.getLocation().subtract(delta));
        }
    }

    private void process(final Node node) {
        if (!node.getProperties().isEmpty() && node.getLocation() == null) {
            node.setLocation(Point3D.ZERO);
        }

        for (final Entry<Node, Activation> assoc : node.getSynapse()) {
            attract(assoc.getKey(), node, .1 * assoc.getValue().getCoefficient());
        }

        for (final Entry<Node, Node> prop : node.getProperties().entrySet()) {
            if (prop.getValue() == null)
                continue;

            if (prop.getKey().getLocation() == null) {
                prop.getKey().setLocation(node.getLocation().add(-.25, 1, 0));
            }
            if (prop.getValue().getLocation() == null) {
                prop.getValue().setLocation(node.getLocation().add(0, 1, 0));
            }
            attract(prop.getKey(), node, .02);
            attract(prop.getValue(), node, .05);
        }

        if (!node.isPinned() && node.getLocation() != null) {
            for (final Link otherLink : getNeighborhood(node)) {
                final Node other = otherLink.get();
                if (other == null || other == node)
                    continue;
                Point3D vec = node.getLocation().subtract(other.getLocation());
                if (vec.equals(Point3D.ZERO)) {
                    vec = new Point3D(.5 - Math.random(), .5 - Math.random(), .5 - Math.random());
                }

                final double dist = vec.magnitude();
                if (dist >= GRID_SIZE)
                    continue;
                node.setLocation(node.getLocation().add(vec.normalize().multiply((GRID_SIZE - dist) * .1)));
            }
        }
    }
}
