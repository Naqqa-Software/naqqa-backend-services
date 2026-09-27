package com.naqqa.elasticsearch.search.bridge.join;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.Objects;
import java.util.TreeMap;

public final class NestedQuery extends Query {

    private final Query childQuery;
    private final ParentChildDocMapping mapping;
    private final JoinScoreMode scoreMode;

    public NestedQuery(Query childQuery, ParentChildDocMapping mapping, JoinScoreMode scoreMode) {
        this.childQuery = Objects.requireNonNull(childQuery);
        this.mapping = Objects.requireNonNull(mapping);
        this.scoreMode = scoreMode;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rewritten = childQuery.rewrite(searcher);
        return rewritten != childQuery ? new NestedQuery(rewritten, mapping, scoreMode) : this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode mode, float boost) throws IOException {
        Weight childWeight = childQuery.createWeight(searcher, ScoreMode.COMPLETE, boost);
        return new NestedWeight(this, childWeight);
    }

    @Override
    public String toString() {
        return "NestedQuery(" + childQuery + ", scoreMode=" + scoreMode + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NestedQuery q && childQuery.equals(q.childQuery) && scoreMode == q.scoreMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(childQuery, scoreMode);
    }

    private TreeMap<Integer, float[]> aggregate(LeafReaderContext context, Weight childWeight) throws IOException {
        TreeMap<Integer, float[]> parentAgg = new TreeMap<>();
        Scorer childScorer = childWeight.scorer(context);
        if (childScorer == null) {
            return parentAgg;
        }
        int doc;
        while ((doc = childScorer.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
            if (!mapping.isChild(doc)) {
                continue;
            }
            int parent = mapping.parentOf(doc);
            float score = childScorer.score();
            float[] agg = parentAgg.get(parent);
            if (agg == null) {
                parentAgg.put(parent, new float[] {score, score, score, 1f});
            } else {
                agg[0] = Math.min(agg[0], score);
                agg[1] = Math.max(agg[1], score);
                agg[2] += score;
                agg[3] += 1f;
            }
        }
        return parentAgg;
    }

    private float combine(float[] agg) {
        float min = agg[0];
        float max = agg[1];
        float sum = agg[2];
        float count = agg[3];
        return switch (scoreMode) {
            case NONE -> 1f;
            case MIN -> min;
            case MAX -> max;
            case SUM -> sum;
            case AVG -> sum / count;
        };
    }

    private final class NestedWeight extends Weight {
        private final Weight childWeight;

        NestedWeight(Query query, Weight childWeight) {
            super(query);
            this.childWeight = childWeight;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            TreeMap<Integer, float[]> agg = aggregate(context, childWeight);
            if (agg.isEmpty()) {
                return null;
            }
            int[] docs = new int[agg.size()];
            float[] scores = new float[agg.size()];
            int i = 0;
            for (var e : agg.entrySet()) {
                docs[i] = e.getKey();
                scores[i] = combine(e.getValue());
                i++;
            }
            return new SortedDocScoreScorer(this, docs, scores);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            TreeMap<Integer, float[]> agg = aggregate(context, childWeight);
            float[] a = agg.get(doc);
            if (a == null) {
                return Explanation.noMatch("no matching nested children for parent doc " + doc);
            }
            return Explanation.match(combine(a), "nested query aggregated " + (int) a[3] + " matching children with " + scoreMode);
        }
    }
}
