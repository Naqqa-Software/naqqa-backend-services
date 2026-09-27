package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;

public abstract class Weight {

    private final Query query;

    protected Weight(Query query) {
        this.query = query;
    }

    public Query getQuery() {
        return query;
    }

    public abstract Scorer scorer(LeafReaderContext context) throws IOException;

    public abstract Explanation explain(LeafReaderContext context, int doc) throws IOException;

    public boolean isCacheable(LeafReaderContext context) {
        return true;
    }
}
