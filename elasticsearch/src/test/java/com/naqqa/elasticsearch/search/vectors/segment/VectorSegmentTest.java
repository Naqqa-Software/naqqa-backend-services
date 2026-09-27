package com.naqqa.elasticsearch.search.vectors.segment;

import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.vectors.Bits;
import com.naqqa.elasticsearch.search.vectors.ElementType;
import com.naqqa.elasticsearch.search.vectors.ExactKnnSearcher;
import com.naqqa.elasticsearch.search.vectors.KnnResults;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswConfig;
import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class VectorSegmentTest {

    private static float[][] randomVectors(SplittableRandom r, int n, int dims) {
        float[][] vectors = new float[n][dims];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < dims; d++) {
                vectors[i][d] = (float) (r.nextDouble() * 2 - 1);
            }
        }
        return vectors;
    }

    private static Set<Integer> docsOf(TopDocs topDocs) {
        Set<Integer> docs = new HashSet<>();
        for (ScoreDoc sd : topDocs.scoreDocs()) {
            docs.add(sd.doc);
        }
        return docs;
    }

    private static Set<Integer> docsOf(KnnResults kr) {
        Set<Integer> docs = new HashSet<>();
        for (int i = 0; i < kr.size(); i++) {
            docs.add(kr.doc(i));
        }
        return docs;
    }

    private static double recall(Set<Integer> approx, Set<Integer> truth) {
        int hits = 0;
        for (int doc : truth) {
            if (approx.contains(doc)) {
                hits++;
            }
        }
        return truth.isEmpty() ? 1.0 : (double) hits / truth.size();
    }

    @Test
    public void writerAndReaderRoundTripRecall() throws Exception {
        SplittableRandom r = new SplittableRandom(11);
        int n = 300;
        int dims = 16;
        int k = 10;
        float[][] vectors = randomVectors(r, n, dims);
        List<VectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            entries.add(VectorEntry.ofFloat(i, vectors[i]));
        }
        Directory dir = new ByteBuffersDirectory();
        VectorSegmentWriter.write(dir, "vec1", n, ElementType.FLOAT, VectorSimilarity.COSINE, entries, HnswConfig.defaults(), QuantizationMode.NONE);
        VectorSegmentReader reader = VectorSegmentReader.open(dir, "vec1", n, VectorSimilarity.COSINE);

        double totalRecall = 0;
        int trials = 5;
        for (int t = 0; t < trials; t++) {
            float[] query = randomVectors(r, 1, dims)[0];
            TopDocs approx = reader.search(query, k, 60, Bits.matchAll(n), false);
            KnnResults truth = ExactKnnSearcher.search(vectors, query, VectorSimilarity.COSINE, k, null);
            totalRecall += recall(docsOf(approx), docsOf(truth));
        }
        double avgRecall = totalRecall / trials;
        assertTrue(avgRecall >= 0.9, "expected recall >= 0.9 but was " + avgRecall);
    }

    @Test
    public void quantizedInt8RecallWithinTolerance() throws Exception {
        SplittableRandom r = new SplittableRandom(22);
        int n = 300;
        int dims = 24;
        int k = 10;
        float[][] vectors = randomVectors(r, n, dims);
        List<VectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            entries.add(VectorEntry.ofFloat(i, vectors[i]));
        }
        Directory dir = new ByteBuffersDirectory();
        VectorSegmentWriter.write(dir, "vec1", n, ElementType.FLOAT, VectorSimilarity.DOT_PRODUCT, entries, HnswConfig.defaults(), QuantizationMode.INT8);
        VectorSegmentReader reader = VectorSegmentReader.open(dir, "vec1", n, VectorSimilarity.DOT_PRODUCT);
        assertEquals(QuantizationMode.INT8, reader.quantization());

        double totalRecall = 0;
        int trials = 5;
        for (int t = 0; t < trials; t++) {
            float[] query = randomVectors(r, 1, dims)[0];
            TopDocs approx = reader.search(query, k, 80, Bits.matchAll(n), false);
            KnnResults truth = ExactKnnSearcher.search(vectors, query, VectorSimilarity.DOT_PRODUCT, k, null);
            totalRecall += recall(docsOf(approx), docsOf(truth));
        }
        double avgRecall = totalRecall / trials;
        assertTrue(avgRecall >= 0.7, "expected quantized recall >= 0.7 but was " + avgRecall);
    }

    @Test
    public void mergeOfTwoSegmentsRespectsLiveDocsAndMatchesBruteForce() throws Exception {
        SplittableRandom r = new SplittableRandom(33);
        int dims = 12;
        int maxDoc0 = 80;
        int maxDoc1 = 60;
        float[][] source0Vectors = randomVectors(r, maxDoc0, dims);
        float[][] source1Vectors = randomVectors(r, maxDoc1, dims);

        List<VectorEntry> entries0 = new ArrayList<>();
        for (int i = 0; i < maxDoc0; i++) {
            entries0.add(VectorEntry.ofFloat(i, source0Vectors[i]));
        }
        List<VectorEntry> entries1 = new ArrayList<>();
        for (int i = 0; i < maxDoc1; i++) {
            entries1.add(VectorEntry.ofFloat(i, source1Vectors[i]));
        }

        Directory dir = new ByteBuffersDirectory();
        HnswConfig config = new HnswConfig(16, 100);
        VectorSegmentWriter.write(dir, "seg0", maxDoc0, ElementType.FLOAT, VectorSimilarity.COSINE, entries0, config, QuantizationMode.NONE);
        VectorSegmentWriter.write(dir, "seg1", maxDoc1, ElementType.FLOAT, VectorSimilarity.COSINE, entries1, config, QuantizationMode.NONE);
        VectorSegmentReader reader0 = VectorSegmentReader.open(dir, "seg0", maxDoc0, VectorSimilarity.COSINE);
        VectorSegmentReader reader1 = VectorSegmentReader.open(dir, "seg1", maxDoc1, VectorSimilarity.COSINE);

        int[] remap0 = new int[maxDoc0];
        int[] remap1 = new int[maxDoc1];
        int next = 0;
        int mergedMaxDoc = 0;
        for (int i = 0; i < maxDoc0; i++) {
            remap0[i] = next++;
        }
        for (int i = 0; i < maxDoc1; i++) {
            remap1[i] = (i % 4 == 0) ? -1 : next++;
        }
        mergedMaxDoc = next;

        float[][] expectedByDoc = new float[mergedMaxDoc][];
        for (int i = 0; i < maxDoc0; i++) {
            expectedByDoc[remap0[i]] = source0Vectors[i];
        }
        for (int i = 0; i < maxDoc1; i++) {
            if (remap1[i] >= 0) {
                expectedByDoc[remap1[i]] = source1Vectors[i];
            }
        }

        VectorSegmentMerger.merge(dir, "merged", List.of(reader0, reader1), List.of(remap0, remap1), mergedMaxDoc,
            VectorSimilarity.COSINE, config, QuantizationMode.NONE);
        VectorSegmentReader merged = VectorSegmentReader.open(dir, "merged", mergedMaxDoc, VectorSimilarity.COSINE);

        int k = 8;
        double totalRecall = 0;
        int trials = 5;
        for (int t = 0; t < trials; t++) {
            float[] query = randomVectors(r, 1, dims)[0];
            TopDocs approx = merged.search(query, k, 60, Bits.matchAll(mergedMaxDoc), false);
            KnnResults truth = ExactKnnSearcher.search(expectedByDoc, query, VectorSimilarity.COSINE, k, doc -> expectedByDoc[doc] != null);
            totalRecall += recall(docsOf(approx), docsOf(truth));
            for (ScoreDoc sd : approx.scoreDocs()) {
                assertTrue(expectedByDoc[sd.doc] != null, "merged result referenced a dropped/absent doc " + sd.doc);
            }
        }
        double avgRecall = totalRecall / trials;
        assertTrue(avgRecall >= 0.85, "expected merged recall >= 0.85 but was " + avgRecall);
    }

    @Test
    public void scoreTransformsMatchHandComputedValues() {
        float[] a = {1f, 0f, 0f};
        float[] b = {0f, 1f, 0f};

        float cosineScore = VectorSimilarity.COSINE.score(a, b);
        assertEquals(0.5, cosineScore, 1e-6);

        float dotScore = VectorSimilarity.DOT_PRODUCT.score(a, b);
        assertEquals(0.5, dotScore, 1e-6);

        float l2Score = VectorSimilarity.L2_NORM.score(a, b);
        double squareDistance = 2.0;
        assertEquals(1.0 / (1.0 + squareDistance), l2Score, 1e-6);

        float[] c = {2f, 0f, 0f};
        float[] d = {2f, 0f, 0f};
        float cosineIdentical = VectorSimilarity.COSINE.score(c, d);
        assertEquals(1.0, cosineIdentical, 1e-6);

        float dotScore2 = VectorSimilarity.DOT_PRODUCT.score(c, d);
        assertEquals((1.0 + 4.0) / 2.0, dotScore2, 1e-6);
    }
}
