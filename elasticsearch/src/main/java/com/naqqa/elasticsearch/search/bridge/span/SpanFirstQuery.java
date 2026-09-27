package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;

import java.io.IOException;
import java.util.Objects;

public final class SpanFirstQuery extends SpanQuery {

    private final SpanQuery match;
    private final int end;

    public SpanFirstQuery(SpanQuery match, int end) {
        this.match = Objects.requireNonNull(match);
        this.end = end;
    }

    public SpanQuery match() {
        return match;
    }

    public int end() {
        return end;
    }

    @Override
    public String field() {
        return match.field();
    }

    @Override
    public SpanWeight createSpanWeight(IndexSearcher searcher, float boost) throws IOException {
        SpanWeight matchWeight = match.createSpanWeight(searcher, boost);
        return new SpanWeight(this, boost) {
            @Override
            public Spans getSpans(LeafReaderContext context) throws IOException {
                Spans inner = matchWeight.getSpans(context);
                return inner == null ? null : new FirstSpans(inner, end);
            }
        };
    }

    @Override
    public String toString() {
        return "SpanFirstQuery(" + match + ", end=" + end + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SpanFirstQuery q && match.equals(q.match) && end == q.end;
    }

    @Override
    public int hashCode() {
        return Objects.hash(match, end);
    }

    static final class FirstSpans extends Spans {
        private final Spans inner;
        private final int limit;

        FirstSpans(Spans inner, int limit) {
            this.inner = inner;
            this.limit = limit;
        }

        @Override
        public int docID() {
            return inner.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return inner.nextDoc();
        }

        @Override
        public int advance(int target) throws IOException {
            return inner.advance(target);
        }

        @Override
        public long cost() {
            return inner.cost();
        }

        @Override
        public int nextStartPosition() throws IOException {
            int st;
            while ((st = inner.nextStartPosition()) != NO_MORE_POSITIONS) {
                if (inner.endPosition() <= limit) {
                    return st;
                }
            }
            return NO_MORE_POSITIONS;
        }

        @Override
        public int startPosition() {
            return inner.startPosition();
        }

        @Override
        public int endPosition() {
            return inner.endPosition();
        }

        @Override
        public int width() {
            return inner.width();
        }
    }
}
