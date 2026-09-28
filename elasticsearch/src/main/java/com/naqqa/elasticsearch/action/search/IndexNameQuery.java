package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.common.regex.Regex;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.util.List;
import java.util.Objects;

public final class IndexNameQuery extends Query {

    private final List<String> patterns;

    public IndexNameQuery(List<String> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    public List<String> patterns() {
        return patterns;
    }

    public boolean matches(String index) {
        if (index == null) {
            return false;
        }
        for (String p : patterns) {
            if (p.equals(index) || (Regex.isSimpleMatchPattern(p) && Regex.simpleMatch(p, index))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new Weight(this) {
            @Override
            public Scorer scorer(LeafReaderContext context) {
                if (!matches(SegmentOwnership.indexOf(context.reader()))) {
                    return null;
                }
                return new AllDocsScorer(this, context.reader().maxDoc(), boost);
            }

            @Override
            public Explanation explain(LeafReaderContext context, int doc) {
                String index = SegmentOwnership.indexOf(context.reader());
                return matches(index) ? Explanation.match(boost, "_index:" + index)
                    : Explanation.noMatch("_index [" + index + "] does not match " + patterns);
            }
        };
    }

    @Override
    public String toString() {
        return "_index:" + patterns;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IndexNameQuery q && patterns.equals(q.patterns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(IndexNameQuery.class, patterns);
    }

    private static final class AllDocsScorer extends Scorer {
        private final int maxDoc;
        private final float score;
        private int doc = -1;

        AllDocsScorer(Weight weight, int maxDoc, float score) {
            super(weight);
            this.maxDoc = maxDoc;
            this.score = score;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() {
            return advance(doc + 1);
        }

        @Override
        public int advance(int target) {
            doc = target >= maxDoc ? DocIdSetIterator.NO_MORE_DOCS : target;
            return doc;
        }

        @Override
        public long cost() {
            return maxDoc;
        }

        @Override
        public float score() {
            return score;
        }

        @Override
        public float getMaxScore(int upTo) {
            return score;
        }
    }
}
