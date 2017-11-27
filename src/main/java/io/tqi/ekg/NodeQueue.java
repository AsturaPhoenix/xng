package io.tqi.ekg;

import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import com.google.common.collect.Iterables;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import lombok.Synchronized;

/**
 * A collection of nodes ordered by most recent activation. This collection
 * holds weak references to its nodes. This collection is thread-safe.
 */
public class NodeQueue implements Iterable<Node> {
    private class Link {
        Link previous, next;
        Node.Ref node;

        Link(final Node node) {
            this.node = node.ref(() -> remove(this));
        }
    }

    private class NodeQueueIterator implements Iterator<Node> {
        final Object version;
        Link nextLink;
        Node nextNode; // hold onto a hard reference while on deck

        NodeQueueIterator() {
            version = NodeQueue.this.version;
            nextLink = head;
        }

        private void consumeReleased() {
            synchronized ($lock) {
                while (nextLink != null && (nextNode = nextLink.node.get()) == null) {
                    nextLink = nextLink.next;
                }
            }
        }

        @Override
        public boolean hasNext() {
            // set nextNode lazily to maximize gc window
            consumeReleased();
            return nextLink != null;
        }

        @Override
        public Node next() {
            synchronized ($lock) {
                if (!hasNext()) // sets nextNode
                    throw new NoSuchElementException();

                if (version != NodeQueue.this.version) {
                    throw new ConcurrentModificationException();
                }

                nextLink = nextLink.next;
            }
            return nextNode;
        }
    }

    private Link head, tail;
    private Object version;

    private Subject<Node> rxActivate = PublishSubject.create();

    public Observable<Node> rxActivate() {
        return rxActivate;
    }

    public Object mutex() {
        return $lock;
    }

    public void add(final Node node) {
        final Link link;
        synchronized ($lock) {
            link = new Link(node);
            initAtTail(link);
        }

        node.rxActivate().subscribe(t -> {
            promote(link);
            rxActivate.onNext(node);
        });
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

        if (link.node.get() != null) {
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

    @Override
    public String toString() {
        return Iterables.toString(this);
    }
}
