package com.naqqa.elasticsearch.search.vectors.query;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.execution.TotalHits;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;
import com.naqqa.elasticsearch.search.vectors.Bits;
import com.naqqa.elasticsearch.search.vectors.segment.VectorSegmentAccessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class KnnVectorQuery extends Query {

    private final String field;
    private final float[] queryVector;
    private final int k;
    private final int numCandidates;
    private final Query filter;
    private final VectorSegmentAccessorProvider accessorProvider;

    public KnnVectorQuery(String field, float[] queryVector, int k, int numCandidates, Query filter,
                           VectorSegmentAccessorProvider accessorProvider) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0");
        }
        this.field = Objects.requireNonNull(field);
        this.queryVector = Objects.requireNonNull(queryVector).clone();
        this.k = k;
        this.numCandidates = Math.max(numCandidates, k);
        this.filter = filter;
        this.accessorProvider = Objects.requireNonNull(accessorProvider);
    }

    public String field() {
        return field;
    }

    public float[] queryVector() {
        return queryVector.clone();
    }

    public int k() {
        return k;
    }

    public int numCandidates() {
        return numCandidates;
    }

    public Query filter() {
        return filter;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        return new KnnVectorWeight(searcher, boost);
    }

    @Override
    public String toString() {
        return "KnnVectorQuery(field=" + field + ", k=" + k + ", numCandidates=" + numCandidates
            + (filter != null ? ", filter=" + filter : "") + ")";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof KnnVectorQuery q)) {
            return false;
        }
        return field.equals(q.field) && Arrays.equals(queryVector, q.queryVector) && k == q.k
            && numCandidates == q.numCandidates && Objects.equals(filter, q.filter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, Arrays.hashCode(queryVector), k, numCandidates, filter);
    }

    private static Bits adaptLiveDocs(FixedBitSet liveDocs, int maxDoc) {
        if (liveDocs == null) {
            return Bits.matchAll(maxDoc);
        }
        return Bits.fromPredicate(liveDocs::get, maxDoc);
    }

    private final class KnnVectorWeight extends Weight {
        private final IndexSearcher searcher;
        private final float boost;

        KnnVectorWeight(IndexSearcher searcher, float boost) {
            super(KnnVectorQuery.this);
            this.searcher = searcher;
            this.boost = boost;
        }

        @Override
        public boolean isCacheable(LeafReaderContext context) {
            return false;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            VectorSegmentAccessor accessor = accessorProvider.get(context, field);
            if (accessor == null) {
                return null;
            }
            int maxDoc = context.reader().maxDoc();
            Bits liveDocsBits = adaptLiveDocs(context.reader().liveDocs(), maxDoc);

            TopDocs topDocs;
            if (filter == null) {
                topDocs = accessor.search(queryVector, k, numCandidates, liveDocsBits, false);
            } else {
                topDocs = searchWithFilter(context, accessor, liveDocsBits, maxDoc);
            }
            if (topDocs == null || topDocs.scoreDocs().length == 0) {
                return null;
            }
            return new ScoreDocArrayScorer(this, topDocs.scoreDocs(), boost);
        }

        private TopDocs searchWithFilter(LeafReaderContext context, VectorSegmentAccessor accessor, Bits liveDocsBits, int maxDoc) throws IOException {
            Weight filterWeight = searcher.createWeight(filter, ScoreMode.COMPLETE_NO_SCORES, 1f);
            Scorer filterScorer = filterWeight.scorer(context);
            if (filterScorer == null) {
                return null;
            }
            boolean[] accepted = new boolean[maxDoc];
            int matched = 0;
            int doc;
            while ((doc = filterScorer.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (context.reader().isLive(doc)) {
                    accepted[doc] = true;
                    matched++;
                }
            }
            if (matched == 0) {
                return null;
            }
            Bits filterBits = Bits.fromPredicate(d -> d >= 0 && d < accepted.length && accepted[d], maxDoc);
            boolean selective = matched <= Math.max(numCandidates, k * 4);
            if (selective) {
                return accessor.search(queryVector, k, matched, filterBits, true);
            }

            int candidateCount = numCandidates;
            List<ScoreDoc> kept = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                TopDocs approx = accessor.search(queryVector, candidateCount, candidateCount, liveDocsBits, false);
                kept = new ArrayList<>();
                for (ScoreDoc sd : approx.scoreDocs()) {
                    if (accepted[sd.doc]) {
                        kept.add(sd);
                    }
                }
                if (kept.size() >= k || candidateCount >= accessor.maxDoc()) {
                    break;
                }
                candidateCount = Math.min(accessor.maxDoc(), candidateCount * 4);
            }
            if (kept.size() < k) {
                return accessor.search(queryVector, k, matched, filterBits, true);
            }
            kept.sort((a, b) -> Float.compare(b.score, a.score));
            List<ScoreDoc> top = kept.subList(0, Math.min(k, kept.size()));
            return new TopDocs(new TotalHits(top.size(), TotalHits.Relation.EQUAL_TO), top.toArray(new ScoreDoc[0]));
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            VectorSegmentAccessor accessor = accessorProvider.get(context, field);
            if (accessor == null || !accessor.hasVector(doc)) {
                return Explanation.noMatch("no vector for doc " + doc + " in field [" + field + "]");
            }
            float[] docVector = accessor.getVector(doc);
            if (docVector == null) {
                return Explanation.noMatch("field [" + field + "] is not a float vector field");
            }
            float score = boost * accessor.similarity().score(queryVector, docVector);
            return Explanation.match(score, "knn score for doc " + doc + " in field [" + field + "], similarity="
                + accessor.similarity().esName());
        }
    }
}
