package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.similarity.CollectionStatistics;
import com.naqqa.elasticsearch.search.similarity.Explanation;
import com.naqqa.elasticsearch.search.similarity.SimScorer;
import com.naqqa.elasticsearch.search.similarity.SimWeight;
import com.naqqa.elasticsearch.search.similarity.Similarity;
import com.naqqa.elasticsearch.search.similarity.TermStatistics;

import java.io.IOException;
import java.util.Objects;

public final class TermQuery extends Query {

    private final Term term;
    private final float fieldBoost;

    public TermQuery(Term term) {
        this(term, 1.0f);
    }

    public TermQuery(Term term, float fieldBoost) {
        this.term = term;
        this.fieldBoost = fieldBoost;
    }

    public Term term() {
        return term;
    }

    public float fieldBoost() {
        return fieldBoost;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        CollectionStatistics collStats = searcher.collectionStatistics(term.field());
        TermStatistics termStats = searcher.termStatistics(term);
        Similarity similarity = searcher.similarity();
        SimWeight simWeight = termStats.docFreq() == 0 ? null : similarity.computeWeight(collStats, termStats);
        return new TermWeight(this, simWeight, similarity, boost * fieldBoost, scoreMode);
    }

    @Override
    public String toString() {
        return "TermQuery(" + term + (fieldBoost != 1.0f ? "^" + fieldBoost : "") + ")";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TermQuery tq)) {
            return false;
        }
        return term.equals(tq.term) && fieldBoost == tq.fieldBoost;
    }

    @Override
    public int hashCode() {
        return Objects.hash(term, fieldBoost);
    }

    private final class TermWeight extends Weight {
        private final SimWeight simWeight;
        private final Similarity similarity;
        private final float totalBoost;
        private final ScoreMode scoreMode;

        TermWeight(Query query, SimWeight simWeight, Similarity similarity, float totalBoost, ScoreMode scoreMode) {
            super(query);
            this.simWeight = simWeight;
            this.similarity = similarity;
            this.totalBoost = totalBoost;
            this.scoreMode = scoreMode;
        }

        private int indexOptions(LeafReaderContext context) {
            var info = context.reader().fieldInfo(term.field());
            return info != null ? info.indexOptions() : PostingsFlags.FREQS;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            if (simWeight == null) {
                return null;
            }
            TermsEnum te = context.reader().terms(term.field());
            if (te == null || !te.seekExact(term.bytes())) {
                return null;
            }
            PostingsEnum postings = te.postings(indexOptions(context));
            NormsReader norms = context.reader().norms(term.field());
            SimScorer simScorer = similarity.simScorer(simWeight);
            return new TermScorer(this, postings, simScorer, norms, totalBoost, scoreMode.needsScores(), te.totalTermFreq());
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            if (simWeight == null) {
                return Explanation.noMatch("no matching term " + term + " in field");
            }
            TermsEnum te = context.reader().terms(term.field());
            if (te == null || !te.seekExact(term.bytes())) {
                return Explanation.noMatch("no matching term " + term + " in this segment");
            }
            PostingsEnum postings = te.postings(indexOptions(context));
            int found = postings.advance(doc);
            if (found != doc) {
                return Explanation.noMatch("no matching term " + term + " in doc " + doc);
            }
            NormsReader norms = context.reader().norms(term.field());
            long norm = norms == null ? 1 : norms.fieldLength(doc);
            SimScorer simScorer = similarity.simScorer(simWeight);
            Explanation freqExplanation = Explanation.match(postings.freq(), "termFreq=" + postings.freq());
            Explanation scoreExplanation = simScorer.explain(postings.freq(), norm, freqExplanation);
            float value = totalBoost * scoreExplanation.value();
            return Explanation.match(value, "weight(" + term + " in doc " + doc + "), product of:",
                Explanation.match(totalBoost, "boost"),
                scoreExplanation);
        }
    }

    static final class TermScorer extends Scorer {
        private final PostingsEnum postings;
        private final SimScorer simScorer;
        private final NormsReader norms;
        private final float boost;
        private final boolean needsScores;
        private final long totalTermFreq;
        private Float cachedMaxScore;

        TermScorer(Weight weight, PostingsEnum postings, SimScorer simScorer, NormsReader norms, float boost,
                   boolean needsScores, long totalTermFreq) {
            super(weight);
            this.postings = postings;
            this.simScorer = simScorer;
            this.norms = norms;
            this.boost = boost;
            this.needsScores = needsScores;
            this.totalTermFreq = totalTermFreq;
        }

        @Override
        public int docID() {
            return postings.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return postings.nextDoc();
        }

        @Override
        public int advance(int target) throws IOException {
            return postings.advance(target);
        }

        @Override
        public long cost() {
            return postings.cost();
        }

        @Override
        public float score() throws IOException {
            if (!needsScores) {
                return boost;
            }
            float freq = postings.freq();
            long norm = norms == null ? 1 : norms.fieldLength(docID());
            return boost * simScorer.score(freq, norm);
        }

        @Override
        public float getMaxScore(int upTo) {
            if (cachedMaxScore == null) {
                float maxFreq = totalTermFreq > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(totalTermFreq, 1);
                long minNorm = 1;
                if (norms != null) {
                    long best = Long.MAX_VALUE;
                    for (int d = 0; d < norms.maxDoc(); d++) {
                        long len = norms.fieldLength(d);
                        if (len > 0 && len < best) {
                            best = len;
                        }
                    }
                    minNorm = best == Long.MAX_VALUE ? 1 : best;
                }
                cachedMaxScore = boost * simScorer.score(maxFreq, minNorm);
            }
            return cachedMaxScore;
        }
    }
}
