package com.naqqa.elasticsearch.search.vectors.query;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.vectors.ElementType;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswConfig;
import com.naqqa.elasticsearch.search.vectors.segment.QuantizationMode;
import com.naqqa.elasticsearch.search.vectors.segment.VectorEntry;
import com.naqqa.elasticsearch.search.vectors.segment.VectorSegmentAccessor;
import com.naqqa.elasticsearch.search.vectors.segment.VectorSegmentReader;
import com.naqqa.elasticsearch.search.vectors.segment.VectorSegmentWriter;
import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.IntPredicate;

import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class KnnVectorQueryTest {

    private static float[][] randomVectors(SplittableRandom r, int n, int dims) {
        float[][] vectors = new float[n][dims];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < dims; d++) {
                vectors[i][d] = (float) (r.nextDouble() * 2 - 1);
            }
        }
        return vectors;
    }

    private static VectorSegmentReader buildReader(float[][] vectors, int n, int dims) throws IOException {
        List<VectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            entries.add(VectorEntry.ofFloat(i, vectors[i]));
        }
        Directory dir = new ByteBuffersDirectory();
        VectorSegmentWriter.write(dir, "vec", n, ElementType.FLOAT, VectorSimilarity.COSINE, entries, HnswConfig.defaults(), QuantizationMode.NONE);
        return VectorSegmentReader.open(dir, "vec", n, VectorSimilarity.COSINE);
    }

    private static IndexSearcher buildSearcher(int n) {
        SimpleLeafReader reader = SimpleLeafReader.builder(n).build();
        return new IndexSearcher(List.of(reader));
    }

    @Test
    public void selectiveFilterRestrictsResultsToMatchingDocs() throws Exception {
        SplittableRandom r = new SplittableRandom(7);
        int n = 200;
        int dims = 10;
        int k = 5;
        float[][] vectors = randomVectors(r, n, dims);
        VectorSegmentReader reader = buildReader(vectors, n, dims);
        IndexSearcher searcher = buildSearcher(n);
        float[] query = randomVectors(r, 1, dims)[0];

        IntPredicate matches = doc -> doc < 6;
        Query filter = new PredicateFilterQuery(n, matches);
        VectorSegmentAccessorProvider provider = (ctx, field) -> reader;
        KnnVectorQuery knnQuery = new KnnVectorQuery("vec", query, k, 50, filter, provider);

        TopDocs results = searcher.search(knnQuery, k);
        assertTrue(results.scoreDocs().length > 0, "expected at least one result");
        for (ScoreDoc sd : results.scoreDocs()) {
            assertTrue(matches.test(sd.doc), "doc " + sd.doc + " does not satisfy the filter");
        }
    }

    @Test
    public void nonSelectiveFilterStillRespectsFilterAndBackfillsCandidates() throws Exception {
        SplittableRandom r = new SplittableRandom(9);
        int n = 400;
        int dims = 12;
        int k = 8;
        float[][] vectors = randomVectors(r, n, dims);
        VectorSegmentReader reader = buildReader(vectors, n, dims);
        IndexSearcher searcher = buildSearcher(n);
        float[] query = randomVectors(r, 1, dims)[0];

        IntPredicate matches = doc -> doc % 2 == 0;
        Query filter = new PredicateFilterQuery(n, matches);
        VectorSegmentAccessorProvider provider = (ctx, field) -> reader;
        KnnVectorQuery knnQuery = new KnnVectorQuery("vec", query, k, 20, filter, provider);

        TopDocs results = searcher.search(knnQuery, k);
        assertTrue(results.scoreDocs().length == k, "expected exactly k results but got " + results.scoreDocs().length);
        for (ScoreDoc sd : results.scoreDocs()) {
            assertTrue(matches.test(sd.doc), "doc " + sd.doc + " does not satisfy the filter");
        }
    }

    @Test
    public void noFilterReturnsUpToKResults() throws Exception {
        SplittableRandom r = new SplittableRandom(13);
        int n = 150;
        int dims = 6;
        int k = 7;
        float[][] vectors = randomVectors(r, n, dims);
        VectorSegmentReader reader = buildReader(vectors, n, dims);
        IndexSearcher searcher = buildSearcher(n);
        float[] query = randomVectors(r, 1, dims)[0];

        VectorSegmentAccessorProvider provider = (ctx, field) -> reader;
        KnnVectorQuery knnQuery = new KnnVectorQuery("vec", query, k, 40, null, provider);

        TopDocs results = searcher.search(knnQuery, k);
        assertTrue(results.scoreDocs().length == k, "expected exactly k results but got " + results.scoreDocs().length);
    }

    private static final class PredicateFilterQuery extends Query {
        private final int maxDoc;
        private final IntPredicate matches;

        PredicateFilterQuery(int maxDoc, IntPredicate matches) {
            this.maxDoc = maxDoc;
            this.matches = matches;
        }

        @Override
        public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
            return new Weight(this) {
                @Override
                public Scorer scorer(LeafReaderContext context) {
                    return new PredicateScorer(this, maxDoc, matches);
                }

                @Override
                public com.naqqa.elasticsearch.search.similarity.Explanation explain(LeafReaderContext context, int doc) {
                    return matches.test(doc)
                        ? com.naqqa.elasticsearch.search.similarity.Explanation.match(1f, "matches predicate")
                        : com.naqqa.elasticsearch.search.similarity.Explanation.noMatch("does not match predicate");
                }
            };
        }

        @Override
        public String toString() {
            return "PredicateFilterQuery";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof PredicateFilterQuery q && maxDoc == q.maxDoc && matches == q.matches;
        }

        @Override
        public int hashCode() {
            return Objects.hash(maxDoc, matches);
        }
    }

    private static final class PredicateScorer extends Scorer {
        private final int maxDoc;
        private final IntPredicate matches;
        private int doc = -1;

        PredicateScorer(Weight weight, int maxDoc, IntPredicate matches) {
            super(weight);
            this.maxDoc = maxDoc;
            this.matches = matches;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() {
            doc++;
            while (doc < maxDoc && !matches.test(doc)) {
                doc++;
            }
            return doc >= maxDoc ? (doc = DocIdSetIterator.NO_MORE_DOCS) : doc;
        }

        @Override
        public int advance(int target) {
            doc = target - 1;
            return nextDoc();
        }

        @Override
        public long cost() {
            return maxDoc;
        }

        @Override
        public float score() {
            return 1f;
        }
    }
}
