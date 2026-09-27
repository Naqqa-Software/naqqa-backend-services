package com.naqqa.elasticsearch.search.bridge.join;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.Objects;
import java.util.TreeSet;

public final class ParentIdQuery extends Query {

    public static final String ID_FIELD = "_id";

    private final String id;
    private final ParentChildDocMapping mapping;

    public ParentIdQuery(String id, ParentChildDocMapping mapping) {
        this.id = Objects.requireNonNull(id);
        this.mapping = Objects.requireNonNull(mapping);
    }

    public String id() {
        return id;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        Weight idWeight = new TermQuery(new Term(ID_FIELD, id)).createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f);
        return new ParentIdWeight(this, idWeight, boost);
    }

    @Override
    public String toString() {
        return "ParentIdQuery(" + id + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ParentIdQuery q && id.equals(q.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    private final class ParentIdWeight extends Weight {
        private final Weight idWeight;
        private final float boost;

        ParentIdWeight(Query query, Weight idWeight, float boost) {
            super(query);
            this.idWeight = idWeight;
            this.boost = boost;
        }

        private int[] matchingChildren(LeafReaderContext context) throws IOException {
            Scorer idScorer = idWeight.scorer(context);
            if (idScorer == null) {
                return new int[0];
            }
            int parentDoc = idScorer.nextDoc();
            if (parentDoc == DocIdSetIterator.NO_MORE_DOCS) {
                return new int[0];
            }
            TreeSet<Integer> children = new TreeSet<>();
            int maxDoc = context.reader().maxDoc();
            for (int doc = 0; doc < maxDoc; doc++) {
                if (mapping.isChild(doc) && mapping.parentOf(doc) == parentDoc) {
                    children.add(doc);
                }
            }
            return children.stream().mapToInt(Integer::intValue).toArray();
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            int[] docs = matchingChildren(context);
            if (docs.length == 0) {
                return null;
            }
            float[] scores = new float[docs.length];
            java.util.Arrays.fill(scores, boost);
            return new SortedDocScoreScorer(this, docs, scores);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            for (int d : matchingChildren(context)) {
                if (d == doc) {
                    return Explanation.match(boost, "doc " + doc + " has parent id [" + id + "]");
                }
            }
            return Explanation.noMatch("doc " + doc + " does not have parent id [" + id + "]");
        }
    }
}
