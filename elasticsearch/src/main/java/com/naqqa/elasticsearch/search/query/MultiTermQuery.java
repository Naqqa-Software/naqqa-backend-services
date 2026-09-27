package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

public abstract class MultiTermQuery extends Query {

    protected final String field;

    protected MultiTermQuery(String field) {
        this.field = field;
    }

    public String field() {
        return field;
    }

    protected abstract TermsEnum getTermsEnum(TermsEnum termsEnum) throws IOException;

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Set<Term> matched = new LinkedHashSet<>();
        for (LeafReaderContext ctx : searcher.leafContexts()) {
            TermsEnum base = ctx.reader().terms(field);
            if (base == null) {
                continue;
            }
            TermsEnum filtered = getTermsEnum(base);
            byte[] t;
            while ((t = filtered.next()) != null) {
                matched.add(new Term(field, t.clone()));
            }
        }
        if (matched.isEmpty()) {
            return new MatchNoDocsQuery();
        }
        BooleanQuery.Builder builder = BooleanQuery.builder();
        for (Term term : matched) {
            builder.add(new TermQuery(term), BooleanQuery.Occur.SHOULD);
        }
        builder.setMinimumShouldMatch(1);
        return builder.build();
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        throw new IllegalStateException(this + " must be rewritten before createWeight() is called");
    }
}
