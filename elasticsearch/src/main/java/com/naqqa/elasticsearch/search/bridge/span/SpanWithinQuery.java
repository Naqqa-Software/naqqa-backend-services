package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;

import java.io.IOException;
import java.util.Objects;

public final class SpanWithinQuery extends SpanQuery {

    private final SpanQuery little;
    private final SpanQuery big;

    public SpanWithinQuery(SpanQuery little, SpanQuery big) {
        this.little = Objects.requireNonNull(little);
        this.big = Objects.requireNonNull(big);
    }

    public SpanQuery little() {
        return little;
    }

    public SpanQuery big() {
        return big;
    }

    @Override
    public String field() {
        return little.field();
    }

    @Override
    public SpanWeight createSpanWeight(IndexSearcher searcher, float boost) throws IOException {
        SpanWeight littleWeight = little.createSpanWeight(searcher, boost);
        SpanWeight bigWeight = big.createSpanWeight(searcher, 1f);
        return new SpanWeight(this, boost) {
            @Override
            public Spans getSpans(LeafReaderContext context) throws IOException {
                Spans littleSpans = littleWeight.getSpans(context);
                Spans bigSpans = bigWeight.getSpans(context);
                if (littleSpans == null || bigSpans == null) {
                    return null;
                }
                return new SpanContainingQuery.ContainWithinSpans(littleSpans, bigSpans, false);
            }
        };
    }

    @Override
    public String toString() {
        return "SpanWithinQuery(little=" + little + ", big=" + big + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SpanWithinQuery q && little.equals(q.little) && big.equals(q.big);
    }

    @Override
    public int hashCode() {
        return Objects.hash(little, big);
    }
}
