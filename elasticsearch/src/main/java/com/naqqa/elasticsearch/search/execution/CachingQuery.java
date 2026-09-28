package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.common.util.FixedBitSet;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.Objects;

public final class CachingQuery extends Query {

    private final Query inner;

    public CachingQuery(Query inner) {
        this.inner = Objects.requireNonNull(inner);
    }

    public Query inner() {
        return inner;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rewritten = inner.rewrite(searcher);
        return rewritten != inner ? new CachingQuery(rewritten) : this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        Weight innerWeight = inner.createWeight(searcher, scoreMode, boost);
        return new CachingWeight(this, innerWeight, QueryCaches.shared());
    }

    @Override
    public String toString() {
        return "Cache(" + inner + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CachingQuery cq && inner.equals(cq.inner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(CachingQuery.class, inner);
    }

    private static final class CachingWeight extends Weight {
        private final Weight inner;
        private final QueryCache cache;

        CachingWeight(Query query, Weight inner, QueryCache cache) {
            super(query);
            this.inner = inner;
            this.cache = cache;
        }

        private Query cacheKeyQuery() {
            return ((CachingQuery) getQuery()).inner();
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            if (!inner.isCacheable(context)) {
                return inner.scorer(context);
            }
            Query key = cacheKeyQuery();
            Object segmentKey = QueryCache.segmentKey(context.reader());
            FixedBitSet cached = cache.get(segmentKey, key);
            if (cached != null) {
                return cached.cardinality() == 0 ? null : new BitSetScorer(this, cached, context.reader().maxDoc());
            }
            Scorer innerScorer = inner.scorer(context);
            int maxDoc = context.reader().maxDoc();
            if (!cache.shouldCache(key, maxDoc)) {
                return innerScorer;
            }
            FixedBitSet bits = new FixedBitSet(maxDoc);
            if (innerScorer != null) {
                int doc;
                while ((doc = innerScorer.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    bits.set(doc);
                }
            }
            cache.put(segmentKey, key, bits);
            return bits.cardinality() == 0 ? null : new BitSetScorer(this, bits, maxDoc);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            return inner.explain(context, doc);
        }

        @Override
        public boolean isCacheable(LeafReaderContext context) {
            return inner.isCacheable(context);
        }
    }

    private static final class BitSetScorer extends Scorer {
        private final FixedBitSet bits;
        private final int maxDoc;
        private int doc = -1;

        BitSetScorer(Weight weight, FixedBitSet bits, int maxDoc) {
            super(weight);
            this.bits = bits;
            this.maxDoc = maxDoc;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() {
            return advance(doc + 1);
        }

        @Override
        public int advance(int target) {
            if (target >= maxDoc) {
                return doc = DocIdSetIterator.NO_MORE_DOCS;
            }
            int next = bits.nextSetBit(target);
            return doc = (next < 0 ? DocIdSetIterator.NO_MORE_DOCS : next);
        }

        @Override
        public long cost() {
            return maxDoc;
        }

        @Override
        public float score() {
            return 0f;
        }
    }
}
