package io.tqi.ekg;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import lombok.Synchronized;

/**
 * A collection of nodes ordered by most recent activation. This collection
 * holds weak references to its nodes. This collection is thread-safe.
 */
public class NodeQueue implements Iterable<Node> {
    private static class Link extends WeakReference<Node> {
        Link previous, next;

        Link(final Node node, final ReferenceQueue<? super Node> q) {
            super(node, q);
        }
    }

    private class NodeQueueIterator implements Iterator<Node> {
        final Object version;
        Link nextLink;
        Node nextNode; // need to hold onto a hard reference while on deck

        NodeQueueIterator() {
            version = NodeQueue.this.version;
            nextLink = head;
            consumeReleased();
        }

        private void consumeReleased() {
            synchronized ($lock) {
                while (nextLink != null && (nextNode = nextLink.get()) == null) {
                    nextLink = nextLink.next;
                }
            }
        }

        @Override
        public boolean hasNext() {
            return nextLink != null;
        }

        @Override
        public Node next() {
            if (!hasNext())
                throw new NoSuchElementException();

            synchronized ($lock) {
                if (version != NodeQueue.this.version) {
                    throw new ConcurrentModificationException();
                }

                nextLink = nextLink.next;
                consumeReleased();
            }
            return nextNode;
        }
    }

    private final ReferenceQueue<Node> removeQueue = new ReferenceQueue<>();
    private Link head, tail;
    private Object version;

    private final Thread remover = new Thread(() -> {
        while (!Thread.interrupted()) {
            try {
                final Link removed = (Link) removeQueue.remove();
                remove(removed);
            } catch (final InterruptedException e) {
                return;
            }
        }
    });

    public NodeQueue() {
        remover.setDaemon(true);
        remover.start();
    }

    public void add(final Node node) {
        final Link link = new Link(node, removeQueue);
        initAtTail(link);

        node.rxActivate().subscribe(t -> promote(link));
    }

    public void addAll(final Collection<Node> nodes) {
        for (final Node node : nodes) {
            add(node);
        }
    }

    @Synchronized
    private void remove(final Link link) {
        if (link.previous == null) {
            head = link.next;
        } else {
            link.previous.next = link.next;
        }

        if (link.next == null) {
            tail = link.previous;
        } else {
            link.next.previous = link.previous;
        }

        if (link.get() != null) {
            version = null;
        }
    }

    @Synchronized
    private void initAtTail(final Link link) {
        if (tail == null) {
            head = tail = link;
        } else {
            link.previous = tail;
            tail.next = link;
            tail = link;
        }

        version = null;
    }

    @Synchronized
    private void promote(final Link link) {
        if (link == head)
            return;

        remove(link);

        link.previous = null;
        link.next = head;
        head.previous = link;
        head = link;

        version = null;
    }

    @Synchronized
    @Override
    public Iterator<Node> iterator() {
        if (version == null) {
            version = new Object();
        }
        return new NodeQueueIterator();
    }
}
