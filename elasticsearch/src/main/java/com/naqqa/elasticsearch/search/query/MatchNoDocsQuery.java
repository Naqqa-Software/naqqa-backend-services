package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.similarity.Explanation;

public final class MatchNoDocsQuery extends Query {

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new Weight(this) {
            @Override
            public Scorer scorer(LeafReaderContext context) {
                return null;
            }

            @Override
            public Explanation explain(LeafReaderContext context, int doc) {
                return Explanation.noMatch("MatchNoDocsQuery matches nothing");
            }
        };
    }

    @Override
    public String toString() {
        return "MatchNoDocsQuery";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MatchNoDocsQuery;
    }

    @Override
    public int hashCode() {
        return MatchNoDocsQuery.class.hashCode();
    }
}
