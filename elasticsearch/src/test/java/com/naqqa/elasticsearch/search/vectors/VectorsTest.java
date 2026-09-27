package com.naqqa.elasticsearch.search.vectors;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.search.vectors.hnsw.HnswConfig;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswGraph;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswGraphBuilder;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswGraphSearcher;
import com.naqqa.elasticsearch.search.vectors.quantization.BinaryQuantizer;
import com.naqqa.elasticsearch.search.vectors.quantization.ScalarQuantizer;
import com.naqqa.elasticsearch.test.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

public class VectorsTest {

    private static float[][] randomVectors(SplittableRandom r, int n, int dims) {
        float[][] vectors = new float[n][dims];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < dims; d++) {
                vectors[i][d] = (float) (r.nextDouble() * 2 - 1);
            }
        }
        return vectors;
    }

    @Test
    public void simdMatchesScalarDotProductAndCosine() {
        SplittableRandom r = new SplittableRandom(1);
        VectorUtilSupport scalar = VectorUtil.scalarSupport();
        VectorUtilSupport simd = VectorUtil.simdSupport();
        if (simd == null) {
            return;
        }
        for (int trial = 0; trial < 50; trial++) {
            int dims = 1 + r.nextInt(300);
            float[] a = new float[dims];
            float[] b = new float[dims];
            for (int i = 0; i < dims; i++) {
                a[i] = (float) (r.nextDouble() * 4 - 2);
                b[i] = (float) (r.nextDouble() * 4 - 2);
            }
            assertEquals(scalar.dotProduct(a, b), simd.dotProduct(a, b), 1e-2f);
            assertEquals(scalar.cosine(a, b), simd.cosine(a, b), 1e-2f);
            assertEquals(scalar.squareDistance(a, b), simd.squareDistance(a, b), 1e-1f);
        }
    }

    @Test
    public void simdMatchesScalarByteOps() {
        SplittableRandom r = new SplittableRandom(2);
        VectorUtilSupport scalar = VectorUtil.scalarSupport();
        VectorUtilSupport simd = VectorUtil.simdSupport();
        if (simd == null) {
            return;
        }
        for (int trial = 0; trial < 50; trial++) {
            int dims = 1 + r.nextInt(200);
            byte[] a = new byte[dims];
            byte[] b = new byte[dims];
            r.nextBytes(a);
            r.nextBytes(b);
            assertEquals(scalar.dotProduct(a, b), simd.dotProduct(a, b));
            assertEquals(scalar.squareDistance(a, b), simd.squareDistance(a, b));
            assertEquals(scalar.xorBitCount(a, b), simd.xorBitCount(a, b));
        }
    }

    @Test
    public void exactKnnBruteForceFindsTrueNearest() {
        SplittableRandom r = new SplittableRandom(3);
        float[][] vectors = randomVectors(r, 200, 16);
        float[] query = randomVectors(r, 1, 16)[0];
        KnnResults results = ExactKnnSearcher.search(vectors, query, VectorSimilarity.COSINE, 5, null);
        assertEquals(5, results.size());
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.score(i - 1) >= results.score(i), "results should be sorted descending");
        }
    }

    @Test
    public void hnswRecallAgainstBruteForce() {
        SplittableRandom r = new SplittableRandom(4);
        int n = 2000;
        int dims = 32;
        float[][] vectors = randomVectors(r, n, dims);
        FloatVectorValues values = FloatVectorValues.of(vectors);
        RandomVectorScorerSupplier supplier = VectorScorers.floatSupplier(values, VectorSimilarity.COSINE);
        HnswConfig config = new HnswConfig(16, 100, 7);
        HnswGraph graph = new HnswGraphBuilder(supplier, config).build();

        int numQueries = 20;
        int k = 10;
        int efSearch = 80;
        int totalOverlap = 0;
        for (int q = 0; q < numQueries; q++) {
            float[] query = randomVectors(r, 1, dims)[0];
            RandomVectorScorer scorer = VectorScorers.floatScorer(values, VectorSimilarity.COSINE, query);
            KnnResults exact = ExactKnnSearcher.search(scorer, k, null);
            KnnResults approx = HnswGraphSearcher.search(scorer, efSearch, graph, null, Long.MAX_VALUE).topK(k);

            Set<Integer> exactDocs = new HashSet<>();
            for (int i = 0; i < exact.size(); i++) {
                exactDocs.add(exact.doc(i));
            }
            int overlap = 0;
            for (int i = 0; i < approx.size(); i++) {
                if (exactDocs.contains(approx.doc(i))) {
                    overlap++;
                }
            }
            totalOverlap += overlap;
        }
        double recall = (double) totalOverlap / (numQueries * k);
        assertTrue(recall >= 0.85, "recall=" + recall);
    }

    @Test
    public void scalarQuantizationInt8RecallIsHigh() {
        SplittableRandom r = new SplittableRandom(5);
        int n = 500;
        int dims = 32;
        float[][] vectors = randomVectors(r, n, dims);
        ScalarQuantizer sq = ScalarQuantizer.fromVectors(vectors, ScalarQuantizer.defaultConfidenceInterval(dims), 7);
        byte[][] quantized = new byte[n][];
        float[] corrections = new float[n];
        for (int i = 0; i < n; i++) {
            quantized[i] = sq.quantize(vectors[i]);
            corrections[i] = sq.correction(quantized[i]);
        }

        int numQueries = 15;
        int k = 10;
        int totalOverlap = 0;
        for (int q = 0; q < numQueries; q++) {
            float[] query = randomVectors(r, 1, dims)[0];
            RandomVectorScorer exactScorer = VectorScorers.floatScorer(FloatVectorValues.of(vectors), VectorSimilarity.DOT_PRODUCT, query);
            KnnResults exact = ExactKnnSearcher.search(exactScorer, k, null);

            byte[] qQuantized = sq.quantize(query);
            float qCorrection = sq.correction(qQuantized);
            float[] approxScores = new float[n];
            for (int i = 0; i < n; i++) {
                approxScores[i] = sq.scoreDotProduct(qQuantized, qCorrection, quantized[i], corrections[i]);
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> Float.compare(approxScores[b], approxScores[a]));

            Set<Integer> exactDocs = new HashSet<>();
            for (int i = 0; i < exact.size(); i++) {
                exactDocs.add(exact.doc(i));
            }
            int overlap = 0;
            for (int i = 0; i < k; i++) {
                if (exactDocs.contains(order[i])) {
                    overlap++;
                }
            }
            totalOverlap += overlap;
        }
        double recall = (double) totalOverlap / (numQueries * k);
        assertTrue(recall >= 0.6, "recall=" + recall);
    }

    @Test
    public void scalarQuantizerInt4PackUnpackRoundTrip() {
        SplittableRandom r = new SplittableRandom(6);
        byte[] original = new byte[37];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) r.nextInt(16);
        }
        byte[] packed = ScalarQuantizer.packInt4(original);
        byte[] unpacked = ScalarQuantizer.unpackInt4(packed, original.length);
        for (int i = 0; i < original.length; i++) {
            assertEquals((int) original[i], (int) unpacked[i], "index " + i);
        }
    }

    @Test
    public void binaryQuantizationApproximatesDotProduct() {
        SplittableRandom r = new SplittableRandom(8);
        int dims = 64;
        float[] centroid = new float[dims];
        float[][] vectors = randomVectors(r, 50, dims);
        for (float[] v : vectors) {
            for (int i = 0; i < dims; i++) {
                centroid[i] += v[i] / vectors.length;
            }
        }
        BinaryQuantizer bq = new BinaryQuantizer(centroid);
        BinaryQuantizer.Quantized[] quantized = new BinaryQuantizer.Quantized[vectors.length];
        for (int i = 0; i < vectors.length; i++) {
            quantized[i] = bq.quantize(vectors[i]);
        }
        double totalRelError = 0;
        int pairs = 0;
        for (int i = 0; i < vectors.length; i++) {
            for (int j = i + 1; j < vectors.length; j++) {
                float exact = VectorUtil.dotProduct(vectors[i], vectors[j]);
                float approx = bq.approximateDotProduct(quantized[i], quantized[j], dims);
                totalRelError += Math.abs(exact - approx);
                pairs++;
            }
        }
        double avgError = totalRelError / pairs;
        assertTrue(avgError < 3.0, "avgError=" + avgError);
    }

    @Test
    public void asymmetricInt4QueryAgainstBinaryDoc() {
        SplittableRandom r = new SplittableRandom(9);
        int dims = 40;
        float[] doc = randomVectors(r, 1, dims)[0];
        float[] centroid = new float[dims];
        BinaryQuantizer bq = new BinaryQuantizer(centroid);
        BinaryQuantizer.Quantized q = bq.quantize(doc);

        byte[] int4Query = bq.quantizeQueryInt4(doc, -1f, 1f);
        byte[] planes = BinaryQuantizer.buildBitPlanes(int4Query);
        long score = BinaryQuantizer.asymmetricScore(planes, q.bits());

        long manual = 0;
        for (int i = 0; i < dims; i++) {
            int bit = (q.bits()[i >> 3] >> (i & 7)) & 1;
            if (bit == 1) {
                manual += (int4Query[i] & 0x0F);
            }
        }
        assertEquals(manual, score);
    }
}
