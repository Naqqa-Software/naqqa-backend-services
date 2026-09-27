package com.naqqa.elasticsearch.search.advanced.runtime;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.TwoPhaseIterator;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public final class RuntimeFieldQuery extends Query {

    private final Function<LeafReaderContext, RuntimeFieldValueSource> sourceFactory;
    private final Predicate<List<Object>> predicate;
    private final String description;

    public RuntimeFieldQuery(Function<LeafReaderContext, RuntimeFieldValueSource> sourceFactory,
                              Predicate<List<Object>> predicate, String description) {
        this.sourceFactory = sourceFactory;
        this.predicate = predicate;
        this.description = description;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        Weight innerWeight = new MatchAllDocsQuery().createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, boost);
        return new Weight(this) {
            @Override
            public Scorer scorer(LeafReaderContext context) throws IOException {
                Scorer approximation = innerWeight.scorer(context);
                if (approximation == null) {
                    return null;
                }
                RuntimeFieldValueSource source = sourceFactory.apply(context);
                TwoPhaseIterator twoPhase = new TwoPhaseIterator(approximation) {
                    @Override
                    public boolean matches() throws IOException {
                        return predicate.test(source.values(approximation.docID()));
                    }

                    @Override
                    public float matchCost() {
                        return 100f;
                    }
                };
                return new RuntimeFieldScorer(this, approximation, twoPhase);
            }

            @Override
            public Explanation explain(LeafReaderContext context, int doc) throws IOException {
                RuntimeFieldValueSource source = sourceFactory.apply(context);
                List<Object> values = source.values(doc);
                boolean match = predicate.test(values);
                return match ? Explanation.match(1f, description + " matches, computed values=" + values)
                    : Explanation.noMatch(description + " does not match, computed values=" + values);
            }
        };
    }

    @Override
    public String toString() {
        return "RuntimeFieldQuery(" + description + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other == this;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    private static final class RuntimeFieldScorer extends Scorer {
        private final Scorer inner;
        private final DocIdSetIterator disi;

        RuntimeFieldScorer(Weight weight, Scorer inner, TwoPhaseIterator twoPhase) {
            super(weight);
            this.inner = inner;
            this.disi = TwoPhaseIterator.asDocIdSetIterator(twoPhase);
        }

        @Override
        public int docID() {
            return disi.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return disi.nextDoc();
        }

        @Override
        public int advance(int target) throws IOException {
            return disi.advance(target);
        }

        @Override
        public long cost() {
            return disi.cost();
        }

        @Override
        public float score() throws IOException {
            return inner.score();
        }
    }
}
