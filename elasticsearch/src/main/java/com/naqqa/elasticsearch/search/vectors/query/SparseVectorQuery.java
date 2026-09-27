package com.naqqa.elasticsearch.search.vectors.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;
import com.naqqa.elasticsearch.search.vectors.segment.sparse.SparseVectorAccessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class SparseVectorQuery extends Query {

    private final String field;
    private final Map<String, Float> queryFeatures;
    private final SparseVectorSegmentAccessorProvider accessorProvider;

    public SparseVectorQuery(String field, Map<String, Float> queryFeatures, SparseVectorSegmentAccessorProvider accessorProvider) {
        this.field = Objects.requireNonNull(field);
        this.queryFeatures = new TreeMap<>(Objects.requireNonNull(queryFeatures));
        this.accessorProvider = Objects.requireNonNull(accessorProvider);
    }

    public String field() {
        return field;
    }

    public Map<String, Float> queryFeatures() {
        return queryFeatures;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        return new SparseVectorWeight(boost);
    }

    private static float dotProduct(Map<String, Float> query, Map<String, Float> doc) {
        float sum = 0f;
        for (Map.Entry<String, Float> e : query.entrySet()) {
            Float w = doc.get(e.getKey());
            if (w != null) {
                sum += e.getValue() * w;
            }
        }
        return sum;
    }

    @Override
    public String toString() {
        return "SparseVectorQuery(field=" + field + ", terms=" + queryFeatures.size() + ")";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SparseVectorQuery q)) {
            return false;
        }
        return field.equals(q.field) && queryFeatures.equals(q.queryFeatures);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, queryFeatures);
    }

    private final class SparseVectorWeight extends Weight {
        private final float boost;

        SparseVectorWeight(float boost) {
            super(SparseVectorQuery.this);
            this.boost = boost;
        }

        @Override
        public boolean isCacheable(LeafReaderContext context) {
            return false;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            SparseVectorAccessor accessor = accessorProvider.get(context, field);
            if (accessor == null) {
                return null;
            }
            List<ScoreDoc> matches = new ArrayList<>();
            int maxDoc = Math.min(accessor.maxDoc(), context.reader().maxDoc());
            for (int doc = 0; doc < maxDoc; doc++) {
                if (!context.reader().isLive(doc) || !accessor.hasFeatures(doc)) {
                    continue;
                }
                float score = dotProduct(queryFeatures, accessor.getFeatures(doc));
                if (score > 0f) {
                    matches.add(new ScoreDoc(doc, score));
                }
            }
            if (matches.isEmpty()) {
                return null;
            }
            return new ScoreDocArrayScorer(this, matches.toArray(new ScoreDoc[0]), boost);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            SparseVectorAccessor accessor = accessorProvider.get(context, field);
            if (accessor == null || !accessor.hasFeatures(doc)) {
                return Explanation.noMatch("no sparse features for doc " + doc + " in field [" + field + "]");
            }
            float score = boost * dotProduct(queryFeatures, accessor.getFeatures(doc));
            if (score <= 0f) {
                return Explanation.noMatch("no overlapping features for doc " + doc);
            }
            return Explanation.match(score, "sparse dot product for doc " + doc + " in field [" + field + "]");
        }
    }
}
