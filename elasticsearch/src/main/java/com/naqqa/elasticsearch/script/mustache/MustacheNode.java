package com.naqqa.elasticsearch.script.mustache;

import java.util.List;

public final class MustacheNode {

    private MustacheNode() {
    }

    public sealed interface Node {
        record Text(String content) implements Node {}
        record Variable(String name, boolean escape) implements Node {}
        record Section(String name, boolean inverted, List<Node> children, String rawBody) implements Node {}
        record Partial(String name) implements Node {}
    }
}
