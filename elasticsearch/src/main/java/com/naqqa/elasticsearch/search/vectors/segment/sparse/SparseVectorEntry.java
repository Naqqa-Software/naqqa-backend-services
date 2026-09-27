package com.naqqa.elasticsearch.search.vectors.segment.sparse;

import java.util.Map;
import java.util.Objects;

public final class SparseVectorEntry {

    private final int docId;
    private final Map<String, Float> features;

    public SparseVectorEntry(int docId, Map<String, Float> features) {
        this.docId = docId;
        this.features = Objects.requireNonNull(features);
    }

    public int docId() {
        return docId;
    }

    public Map<String, Float> features() {
        return features;
    }
}
