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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class HasParentQuery extends Query {

    private final Query parentQuery;
    private final ParentChildDocMapping mapping;
    private final boolean scoreEnabled;

    public HasParentQuery(Query parentQuery, ParentChildDocMapping mapping, boolean scoreEnabled) {
        this.parentQuery = Objects.requireNonNull(parentQuery);
        this.mapping = Objects.requireNonNull(mapping);
        this.scoreEnabled = scoreEnabled;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rewritten = parentQuery.rewrite(searcher);
        return rewritten != parentQuery ? new HasParentQuery(rewritten, mapping, scoreEnabled) : this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode mode, float boost) throws IOException {
        Weight parentWeight = parentQuery.createWeight(searcher, ScoreMode.COMPLETE, boost);
        return new HasParentWeight(this, parentWeight, boost);
    }

    @Override
    public String toString() {
        return "HasParentQuery(" + parentQuery + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HasParentQuery q && parentQuery.equals(q.parentQuery) && scoreEnabled == q.scoreEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(parentQuery, scoreEnabled);
    }

    private final class HasParentWeight extends Weight {
        private final Weight parentWeight;
        private final float boost;

        HasParentWeight(Query query, Weight parentWeight, float boost) {
            super(query);
            this.parentWeight = parentWeight;
            this.boost = boost;
        }

        private TreeMap<Integer, Float> matchingChildren(LeafReaderContext context) throws IOException {
            Scorer parentScorer = parentWeight.scorer(context);
            Map<Integer, Float> parentScores = new HashMap<>();
            if (parentScorer != null) {
                int p;
                while ((p = parentScorer.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    parentScores.put(p, parentScorer.score());
                }
            }
            TreeMap<Integer, Float> childScores = new TreeMap<>();
            if (parentScores.isEmpty()) {
                return childScores;
            }
            int maxDoc = context.reader().maxDoc();
            for (int doc = 0; doc < maxDoc; doc++) {
                if (!mapping.isChild(doc)) {
                    continue;
                }
                Float parentScore = parentScores.get(mapping.parentOf(doc));
                if (parentScore != null) {
                    childScores.put(doc, scoreEnabled ? parentScore * boost : boost);
                }
            }
            return childScores;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            TreeMap<Integer, Float> childScores = matchingChildren(context);
            if (childScores.isEmpty()) {
                return null;
            }
            int[] docs = new int[childScores.size()];
            float[] scores = new float[childScores.size()];
            int i = 0;
            for (var e : childScores.entrySet()) {
                docs[i] = e.getKey();
                scores[i] = e.getValue();
                i++;
            }
            return new SortedDocScoreScorer(this, docs, scores);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            TreeMap<Integer, Float> childScores = matchingChildren(context);
            Float score = childScores.get(doc);
            if (score == null) {
                return Explanation.noMatch("doc " + doc + " has no matching parent");
            }
            return Explanation.match(score, "has_parent match via parent join mapping");
        }
    }
}
