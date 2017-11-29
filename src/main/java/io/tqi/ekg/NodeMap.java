package io.tqi.ekg;

import java.io.Serializable;

public class NodeMap extends NodeValueMap<Node> implements Serializable {
    private static final long serialVersionUID = -4775598967533688683L;

    @Override
    protected void initBacking() {
        backing = new NodeKeyMap<>();
    }
}
