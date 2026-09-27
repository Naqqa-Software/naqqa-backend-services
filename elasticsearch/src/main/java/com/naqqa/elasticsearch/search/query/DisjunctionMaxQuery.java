package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DisjunctionMaxQuery extends Query {

    private final List<Query> subQueries;
    private final float tieBreaker;

    public DisjunctionMaxQuery(List<Query> subQueries, float tieBreaker) {
        if (subQueries.isEmpty()) {
            throw new IllegalArgumentException("subQueries cannot be empty");
        }
        this.subQueries = List.copyOf(subQueries);
        this.tieBreaker = tieBreaker;
    }

    public List<Query> subQueries() {
        return subQueries;
    }

    public float tieBreaker() {
        return tieBreaker;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        boolean changed = false;
        List<Query> rewritten = new ArrayList<>(subQueries.size());
        for (Query q : subQueries) {
            Query r = q.rewrite(searcher);
            if (r != q) {
                changed = true;
            }
            rewritten.add(r);
        }
        return changed ? new DisjunctionMaxQuery(rewritten, tieBreaker) : this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        List<Weight> subWeights = new ArrayList<>(subQueries.size());
        for (Query q : subQueries) {
            subWeights.add(q.createWeight(searcher, scoreMode, boost));
        }
        return new DisjunctionMaxWeight(this, subWeights, tieBreaker);
    }

    @Override
    public String toString() {
        return "DisjunctionMax(" + subQueries + ", tieBreaker=" + tieBreaker + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DisjunctionMaxQuery dmq && tieBreaker == dmq.tieBreaker && subQueries.equals(dmq.subQueries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subQueries, tieBreaker);
    }

    private static final class DisjunctionMaxWeight extends Weight {
        private final List<Weight> subWeights;
        private final float tieBreaker;

        DisjunctionMaxWeight(Query query, List<Weight> subWeights, float tieBreaker) {
            super(query);
            this.subWeights = subWeights;
            this.tieBreaker = tieBreaker;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            List<Scorer> scorers = new ArrayList<>();
            for (Weight w : subWeights) {
                Scorer s = w.scorer(context);
                if (s != null) {
                    scorers.add(s);
                }
            }
            if (scorers.isEmpty()) {
                return null;
            }
            if (scorers.size() == 1) {
                return scorers.get(0);
            }
            return new DisjunctionMaxScorer(this, scorers, tieBreaker);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            List<Explanation> matches = new ArrayList<>();
            float max = 0f;
            float otherSum = 0f;
            boolean any = false;
            for (Weight w : subWeights) {
                Explanation e = w.explain(context, doc);
                if (e.isMatch()) {
                    any = true;
                    if (e.value() > max) {
                        otherSum += max;
                        max = e.value();
                    } else {
                        otherSum += e.value();
                    }
                    matches.add(e);
                }
            }
            if (!any) {
                return Explanation.noMatch("no matching clause");
            }
            float value = max + tieBreaker * otherSum;
            return Explanation.match(value, "max plus " + tieBreaker + " times others of:", matches);
        }
    }

    static final class DisjunctionMaxScorer extends Scorer {
        private final Scorer[] scorers;
        private final float tieBreaker;
        private int doc = -1;
        private float score;
        private final long costEstimate;

        DisjunctionMaxScorer(Weight weight, List<Scorer> scorers, float tieBreaker) {
            super(weight);
            this.scorers = scorers.toArray(new Scorer[0]);
            this.tieBreaker = tieBreaker;
            long cost = 0;
            for (Scorer s : this.scorers) {
                cost += s.cost();
            }
            this.costEstimate = cost;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() throws IOException {
            if (doc == NO_MORE_DOCS) {
                return NO_MORE_DOCS;
            }
            return doNext(doc + 1);
        }

        @Override
        public int advance(int target) throws IOException {
            if (doc == NO_MORE_DOCS) {
                return NO_MORE_DOCS;
            }
            return doNext(target);
        }

        private int doNext(int target) throws IOException {
            int candidate = NO_MORE_DOCS;
            for (Scorer s : scorers) {
                if (s.docID() < target) {
                    s.advance(target);
                }
                if (s.docID() < candidate) {
                    candidate = s.docID();
                }
            }
            if (candidate == NO_MORE_DOCS) {
                doc = NO_MORE_DOCS;
                return NO_MORE_DOCS;
            }
            float max = 0f;
            float otherSum = 0f;
            for (Scorer s : scorers) {
                if (s.docID() == candidate) {
                    float sc = s.score();
                    if (sc > max) {
                        otherSum += max;
                        max = sc;
                    } else {
                        otherSum += sc;
                    }
                }
            }
            doc = candidate;
            score = max + tieBreaker * otherSum;
            return doc;
        }

        @Override
        public float score() {
            return score;
        }

        @Override
        public long cost() {
            return costEstimate;
        }

        @Override
        public float getMaxScore(int upTo) throws IOException {
            float sum = 0f;
            for (Scorer s : scorers) {
                sum += s.getMaxScore(upTo);
            }
            return sum;
        }
    }
}
