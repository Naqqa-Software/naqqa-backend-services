package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SpanMultiTermQueryWrapper extends SpanQuery {

    private final Query matchQuery;
    private final String field;

    public SpanMultiTermQueryWrapper(Query matchQuery, String field) {
        this.matchQuery = Objects.requireNonNull(matchQuery);
        this.field = Objects.requireNonNull(field);
    }

    public Query matchQuery() {
        return matchQuery;
    }

    @Override
    public String field() {
        return field;
    }

    @Override
    public SpanWeight createSpanWeight(IndexSearcher searcher, float boost) throws IOException {
        Query rewritten = searcher.rewrite(matchQuery);
        List<Term> terms = new ArrayList<>();
        collectTerms(rewritten, terms);
        List<SpanQuery> termSpanQueries = new ArrayList<>(terms.size());
        for (Term t : terms) {
            termSpanQueries.add(new SpanTermQuery(t));
        }
        List<SpanWeight> weights = new ArrayList<>(termSpanQueries.size());
        for (SpanQuery q : termSpanQueries) {
            weights.add(q.createSpanWeight(searcher, boost));
        }
        return new SpanWeight(this, boost) {
            @Override
            public Spans getSpans(LeafReaderContext context) throws IOException {
                List<Spans> subs = new ArrayList<>(weights.size());
                for (SpanWeight w : weights) {
                    Spans s = w.getSpans(context);
                    if (s != null) {
                        subs.add(s);
                    }
                }
                return subs.isEmpty() ? null : new SpanOrQuery.OrSpans(subs);
            }
        };
    }

    private static void collectTerms(Query query, List<Term> out) {
        if (query instanceof TermQuery tq) {
            out.add(tq.term());
        } else if (query instanceof BooleanQuery bq) {
            for (BooleanQuery.BooleanClause clause : bq.clauses()) {
                collectTerms(clause.query(), out);
            }
        }
    }

    @Override
    public String toString() {
        return "SpanMultiTermQueryWrapper(" + matchQuery + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SpanMultiTermQueryWrapper q && matchQuery.equals(q.matchQuery) && field.equals(q.field);
    }

    @Override
    public int hashCode() {
        return Objects.hash(matchQuery, field);
    }
}
