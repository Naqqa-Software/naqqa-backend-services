package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Term;

import java.io.IOException;
import java.util.Objects;

public final class SpanTermQuery extends SpanQuery {

    private final Term term;

    public SpanTermQuery(Term term) {
        this.term = Objects.requireNonNull(term);
    }

    public Term term() {
        return term;
    }

    @Override
    public String field() {
        return term.field();
    }

    @Override
    public SpanWeight createSpanWeight(IndexSearcher searcher, float boost) {
        return new SpanWeight(this, boost) {
            @Override
            public Spans getSpans(LeafReaderContext context) throws IOException {
                TermsEnum te = context.reader().terms(term.field());
                if (te == null || !te.seekExact(term.bytes())) {
                    return null;
                }
                return new TermSpans(te.postings(PostingsFlags.POSITIONS));
            }
        };
    }

    @Override
    public String toString() {
        return "SpanTermQuery(" + term + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SpanTermQuery q && term.equals(q.term);
    }

    @Override
    public int hashCode() {
        return term.hashCode();
    }
}
