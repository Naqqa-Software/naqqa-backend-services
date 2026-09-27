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

public final class HasChildQuery extends Query {

    private final Query childQuery;
    private final ParentChildDocMapping mapping;
    private final JoinScoreMode scoreMode;
    private final Integer minChildren;
    private final Integer maxChildren;

    public HasChildQuery(Query childQuery, ParentChildDocMapping mapping, JoinScoreMode scoreMode,
                          Integer minChildren, Integer maxChildren) {
        this.childQuery = Objects.requireNonNull(childQuery);
        this.mapping = Objects.requireNonNull(mapping);
        this.scoreMode = scoreMode;
        this.minChildren = minChildren;
        this.maxChildren = maxChildren;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rewritten = childQuery.rewrite(searcher);
        return rewritten != childQuery ? new HasChildQuery(rewritten, mapping, scoreMode, minChildren, maxChildren) : this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode mode, float boost) throws IOException {
        Weight childWeight = childQuery.createWeight(searcher, ScoreMode.COMPLETE, boost);
        return new HasChildWeight(this, childWeight);
    }

    @Override
    public String toString() {
        return "HasChildQuery(" + childQuery + ", scoreMode=" + scoreMode + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HasChildQuery q && childQuery.equals(q.childQuery) && scoreMode == q.scoreMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(childQuery, scoreMode);
    }

    private boolean withinChildBounds(int count) {
        if (minChildren != null && count < minChildren) {
            return false;
        }
        return maxChildren == null || count <= maxChildren;
    }

    private float combine(float min, float max, float sum, float count) {
        return switch (scoreMode) {
            case NONE -> 1f;
            case MIN -> min;
            case MAX -> max;
            case SUM -> sum;
            case AVG -> sum / count;
        };
    }

    private final class HasChildWeight extends Weight {
        private final Weight childWeight;

        HasChildWeight(Query query, Weight childWeight) {
            super(query);
            this.childWeight = childWeight;
        }

        private TreeMap<Integer, float[]> aggregate(LeafReaderContext context) throws IOException {
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

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            TreeMap<Integer, float[]> agg = aggregate(context);
            java.util.List<Integer> docs = new java.util.ArrayList<>();
            java.util.List<Float> scores = new java.util.ArrayList<>();
            for (var e : agg.entrySet()) {
                float[] a = e.getValue();
                if (!withinChildBounds((int) a[3])) {
                    continue;
                }
                docs.add(e.getKey());
                scores.add(combine(a[0], a[1], a[2], a[3]));
            }
            if (docs.isEmpty()) {
                return null;
            }
            int[] docArr = docs.stream().mapToInt(Integer::intValue).toArray();
            float[] scoreArr = new float[scores.size()];
            for (int i = 0; i < scoreArr.length; i++) {
                scoreArr[i] = scores.get(i);
            }
            return new SortedDocScoreScorer(this, docArr, scoreArr);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            TreeMap<Integer, float[]> agg = aggregate(context);
            float[] a = agg.get(doc);
            if (a == null || !withinChildBounds((int) a[3])) {
                return Explanation.noMatch("doc " + doc + " has no matching children within bounds");
            }
            return Explanation.match(combine(a[0], a[1], a[2], a[3]), "has_child aggregated " + (int) a[3] + " children with " + scoreMode);
        }
    }
}
