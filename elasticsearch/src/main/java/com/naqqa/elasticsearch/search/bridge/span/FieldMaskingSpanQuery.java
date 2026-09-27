package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;

import java.io.IOException;
import java.util.Objects;

public final class FieldMaskingSpanQuery extends SpanQuery {

    private final SpanQuery inner;
    private final String maskedField;

    public FieldMaskingSpanQuery(SpanQuery inner, String maskedField) {
        this.inner = Objects.requireNonNull(inner);
        this.maskedField = Objects.requireNonNull(maskedField);
    }

    public SpanQuery inner() {
        return inner;
    }

    @Override
    public String field() {
        return maskedField;
    }

    @Override
    public SpanWeight createSpanWeight(IndexSearcher searcher, float boost) throws IOException {
        SpanWeight innerWeight = inner.createSpanWeight(searcher, boost);
        return new SpanWeight(this, boost) {
            @Override
            public Spans getSpans(LeafReaderContext context) throws IOException {
                return innerWeight.getSpans(context);
            }
        };
    }

    @Override
    public String toString() {
        return "FieldMaskingSpanQuery(" + inner + ", field=" + maskedField + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FieldMaskingSpanQuery q && inner.equals(q.inner) && maskedField.equals(q.maskedField);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inner, maskedField);
    }
}
