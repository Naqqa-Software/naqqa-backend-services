package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;

public final class MatchAllDocsQuery extends Query {

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new MatchAllWeight(this, boost);
    }

    @Override
    public String toString() {
        return "MatchAllDocsQuery";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MatchAllDocsQuery;
    }

    @Override
    public int hashCode() {
        return MatchAllDocsQuery.class.hashCode();
    }

    private static final class MatchAllWeight extends Weight {
        private final float boost;

        MatchAllWeight(Query query, float boost) {
            super(query);
            this.boost = boost;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) {
            return new MatchAllScorer(this, context.reader().maxDoc(), boost);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) {
            return Explanation.match(boost, "boost, MatchAllDocsQuery matches every document");
        }
    }

    private static final class MatchAllScorer extends Scorer {
        private final int maxDoc;
        private final float boost;
        private int doc = -1;

        MatchAllScorer(Weight weight, int maxDoc, float boost) {
            super(weight);
            this.maxDoc = maxDoc;
            this.boost = boost;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() {
            doc = doc + 1 < maxDoc ? doc + 1 : NO_MORE_DOCS;
            return doc;
        }

        @Override
        public int advance(int target) {
            doc = target < maxDoc ? target : NO_MORE_DOCS;
            return doc;
        }

        @Override
        public long cost() {
            return maxDoc;
        }

        @Override
        public float score() {
            return boost;
        }

        @Override
        public float getMaxScore(int upTo) {
            return boost;
        }
    }
}
