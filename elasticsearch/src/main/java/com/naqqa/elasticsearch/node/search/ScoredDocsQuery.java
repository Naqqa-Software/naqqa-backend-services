package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class ScoredDocsQuery extends Query {

    private final String description;
    private final TreeMap<Integer, Float> scores;

    ScoredDocsQuery(String description, Map<Integer, Float> scores) {
        this.description = description;
        this.scores = new TreeMap<>(scores);
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new Weight(this) {
            @Override
            public Scorer scorer(LeafReaderContext context) {
                int base = context.docBase();
                int max = base + context.reader().maxDoc();
                Map<Integer, Float> slice = scores.subMap(base, max);
                if (slice.isEmpty()) {
                    return null;
                }
                int[] docs = new int[slice.size()];
                float[] values = new float[slice.size()];
                int i = 0;
                for (Map.Entry<Integer, Float> e : slice.entrySet()) {
                    docs[i] = e.getKey() - base;
                    values[i] = e.getValue() * boost;
                    i++;
                }
                return new ArrayScorer(this, docs, values);
            }

            @Override
            public boolean isCacheable(LeafReaderContext context) {
                return false;
            }

            @Override
            public Explanation explain(LeafReaderContext context, int doc) {
                Float score = scores.get(context.docBase() + doc);
                return score == null ? Explanation.noMatch("doc not in " + description)
                    : Explanation.match(score * boost, description + " score");
            }
        };
    }

    @Override
    public String toString() {
        return description + scores.keySet();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ScoredDocsQuery q && description.equals(q.description) && scores.equals(q.scores);
    }

    @Override
    public int hashCode() {
        return Objects.hash(description, scores);
    }

    private static final class ArrayScorer extends Scorer {
        private final int[] docs;
        private final float[] scores;
        private int index = -1;

        ArrayScorer(Weight weight, int[] docs, float[] scores) {
            super(weight);
            this.docs = docs;
            this.scores = scores;
        }

        @Override
        public int docID() {
            if (index < 0) {
                return -1;
            }
            return index >= docs.length ? DocIdSetIterator.NO_MORE_DOCS : docs[index];
        }

        @Override
        public int nextDoc() {
            index++;
            return docID();
        }

        @Override
        public int advance(int target) {
            int from = Math.max(0, index + 1);
            if (from >= docs.length) {
                index = docs.length;
                return docID();
            }
            int pos = Arrays.binarySearch(docs, from, docs.length, target);
            index = pos >= 0 ? pos : -pos - 1;
            return docID();
        }

        @Override
        public long cost() {
            return docs.length;
        }

        @Override
        public float score() {
            return index >= 0 && index < docs.length ? scores[index] : 0f;
        }

        @Override
        public float getMaxScore(int upTo) {
            float max = 0f;
            for (float s : scores) {
                max = Math.max(max, s);
            }
            return max;
        }
    }
}
