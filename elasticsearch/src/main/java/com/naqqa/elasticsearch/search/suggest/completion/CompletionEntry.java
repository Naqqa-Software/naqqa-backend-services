package com.naqqa.elasticsearch.search.suggest.completion;

import java.util.List;
import java.util.Map;

public record CompletionEntry(String text, long weight, Map<String, List<String>> contexts, long insertionOrder) {

    public static final class Input {
        private final String text;
        private final long weight;
        private final Map<String, List<String>> contexts;

        public Input(String text, long weight, Map<String, List<String>> contexts) {
            this.text = text;
            this.weight = weight;
            this.contexts = contexts == null ? Map.of() : contexts;
        }

        public Input(String text, long weight) {
            this(text, weight, Map.of());
        }

        public String text() {
            return text;
        }

        public long weight() {
            return weight;
        }

        public Map<String, List<String>> contexts() {
            return contexts;
        }
    }
}
