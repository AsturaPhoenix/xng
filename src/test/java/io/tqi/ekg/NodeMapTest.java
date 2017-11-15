package io.tqi.ekg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class NodeMapTest {
    @Test
    public void testKeyDeletion() throws Exception {
        final NodeKeyMap<String> map = new NodeKeyMap<>();
        final Node a = new Node(), b = new Node();
        map.put(a, "foo");
        map.put(b, "bar");

        b.delete();

        assertEquals("foo", map.get(a));
        assertFalse(map.containsKey(b));
    }

    @Test
    public void testValueDeletion() throws Exception {
        final NodeValueMap<String> map = new NodeValueMap<>();
        final Node a = new Node(), b = new Node();
        map.put("foo", a);
        map.put("bar", b);
        map.put("baz", b);

        b.delete();

        assertEquals(a, map.get("foo"));
        assertFalse(map.containsKey("bar"));
        assertNull(map.get("baz"));
    }

    @Test
    public void testDualDeletion() throws Exception {
        final NodeMap map = new NodeMap();
        final Node a = new Node(), b = new Node(), c = new Node(), d = new Node();
        map.put(a, b);
        map.put(b, c);
        map.put(c, a);
        map.put(d, d);

        c.delete();
        d.delete();

        assertEquals(b, map.get(a));
        assertFalse(map.containsKey(b));
        assertNull(map.get(c));
        assertFalse(map.containsKey(d));
    }
}
