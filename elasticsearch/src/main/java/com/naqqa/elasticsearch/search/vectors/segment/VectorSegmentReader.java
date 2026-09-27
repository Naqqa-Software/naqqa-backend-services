package com.naqqa.elasticsearch.search.vectors.segment;

import com.naqqa.elasticsearch.codec.vectors.VectorsReader;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.execution.TotalHits;
import com.naqqa.elasticsearch.search.vectors.ArrayByteVectorValues;
import com.naqqa.elasticsearch.search.vectors.ArrayFloatVectorValues;
import com.naqqa.elasticsearch.search.vectors.Bits;
import com.naqqa.elasticsearch.search.vectors.ByteVectorValues;
import com.naqqa.elasticsearch.search.vectors.ElementType;
import com.naqqa.elasticsearch.search.vectors.ExactKnnSearcher;
import com.naqqa.elasticsearch.search.vectors.FloatVectorValues;
import com.naqqa.elasticsearch.search.vectors.KnnResults;
import com.naqqa.elasticsearch.search.vectors.RandomVectorScorer;
import com.naqqa.elasticsearch.search.vectors.VectorScorers;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswGraph;
import com.naqqa.elasticsearch.search.vectors.hnsw.KnnSearch;
import com.naqqa.elasticsearch.search.vectors.quantization.BinaryQuantizer;
import com.naqqa.elasticsearch.search.vectors.quantization.ScalarQuantizer;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;

public final class VectorSegmentReader implements VectorSegmentAccessor {

    private static final int EXACT_SEARCH_THRESHOLD = 32;

    private final VectorsReader raw;
    private final VectorSegmentCodec.SegmentGraphData graphData;
    private final VectorSimilarity similarity;
    private final int maxDoc;
    private final Bits liveDocs;
    private final FloatVectorValues floatValues;
    private final ByteVectorValues byteValues;

    private VectorSegmentReader(VectorsReader raw, VectorSegmentCodec.SegmentGraphData graphData,
                                 VectorSimilarity similarity, int maxDoc, Bits liveDocs) {
        this.raw = raw;
        this.graphData = graphData;
        this.similarity = similarity;
        this.maxDoc = maxDoc;
        this.liveDocs = liveDocs == null ? Bits.matchAll(maxDoc) : liveDocs;
        if (graphData.elementType == ElementType.FLOAT) {
            int n = graphData.ordToDoc.length;
            float[][] byOrd = new float[n][];
            for (int i = 0; i < n; i++) {
                byOrd[i] = raw.floatVector(graphData.ordToDoc[i]);
            }
            this.floatValues = new ArrayFloatVectorValues(byOrd, graphData.ordToDoc);
            this.byteValues = null;
        } else {
            int n = graphData.ordToDoc.length;
            byte[][] byOrd = new byte[n][];
            for (int i = 0; i < n; i++) {
                byOrd[i] = raw.byteVector(graphData.ordToDoc[i]);
            }
            this.byteValues = new ArrayByteVectorValues(byOrd, graphData.ordToDoc, graphData.elementType);
            this.floatValues = null;
        }
    }

    public static VectorSegmentReader open(Directory dir, String fileName, int maxDoc, VectorSimilarity similarity) throws IOException {
        return open(dir, fileName, maxDoc, similarity, null);
    }

    public static VectorSegmentReader open(Directory dir, String fileName, int maxDoc, VectorSimilarity similarity, Bits liveDocs) throws IOException {
        VectorsReader raw;
        try (IndexInput in = dir.openInput(fileName, IOContext.READ)) {
            raw = new VectorsReader(in);
        }
        VectorSegmentCodec.SegmentGraphData data = VectorSegmentCodec.decode(raw.annGraphBlob());
        return new VectorSegmentReader(raw, data, similarity, maxDoc, liveDocs);
    }

    @Override
    public int maxDoc() {
        return maxDoc;
    }

    @Override
    public int dims() {
        return raw.dims();
    }

    @Override
    public ElementType elementType() {
        return graphData.elementType;
    }

    @Override
    public VectorSimilarity similarity() {
        return similarity;
    }

    @Override
    public Bits liveDocs() {
        return liveDocs;
    }

    @Override
    public boolean hasVector(int docId) {
        return graphData.elementType == ElementType.FLOAT ? raw.floatVector(docId) != null : raw.byteVector(docId) != null;
    }

    @Override
    public float[] getVector(int docId) {
        return graphData.elementType == ElementType.FLOAT ? raw.floatVector(docId) : null;
    }

    @Override
    public byte[] getByteVector(int docId) {
        return graphData.elementType == ElementType.FLOAT ? null : raw.byteVector(docId);
    }

    public QuantizationMode quantization() {
        return graphData.quantization;
    }

    public HnswGraph graph() {
        return graphData.graph;
    }

    public int maxOrdCount() {
        return graphData.ordToDoc.length;
    }

    public int ordToDoc(int ord) {
        return graphData.ordToDoc[ord];
    }

    @Override
    public TopDocs search(float[] queryVector, int k, int numCandidates, Bits acceptDocs, boolean forceExact) throws IOException {
        if (graphData.elementType != ElementType.FLOAT) {
            throw new IllegalStateException("field element_type is not float, use searchBytes()");
        }
        RandomVectorScorer rawScorer = VectorScorers.floatScorer(floatValues, similarity, queryVector);
        int ordCount = floatValues.size();
        if (forceExact || graphData.graph == null || ordCount == 0 || ordCount <= EXACT_SEARCH_THRESHOLD) {
            Bits acceptOrds = rawScorer.acceptOrds(acceptDocs);
            KnnResults kr = ExactKnnSearcher.search(rawScorer, Math.max(1, k), acceptOrds);
            return toTopDocs(kr);
        }
        if (graphData.quantization == QuantizationMode.NONE) {
            KnnResults kr = KnnSearch.search(rawScorer, graphData.graph, k, numCandidates, acceptDocs);
            return toTopDocs(kr);
        }
        RandomVectorScorer approxScorer = buildApproxScorer(queryVector, rawScorer);
        int candidateCount = Math.max(k, numCandidates);
        KnnResults approxCandidates = KnnSearch.searchCandidates(approxScorer, graphData.graph, candidateCount, acceptDocs);
        KnnResults rescored = ExactKnnSearcher.rescore(approxCandidates, rawScorer, k);
        return toTopDocs(rescored);
    }

    @Override
    public TopDocs searchBytes(byte[] queryVector, int k, int numCandidates, Bits acceptDocs, boolean forceExact) throws IOException {
        if (graphData.elementType == ElementType.FLOAT) {
            throw new IllegalStateException("field element_type is float, use search()");
        }
        RandomVectorScorer scorer = VectorScorers.byteScorer(byteValues, similarity, queryVector);
        int ordCount = byteValues.size();
        if (forceExact || graphData.graph == null || ordCount == 0 || ordCount <= EXACT_SEARCH_THRESHOLD) {
            Bits acceptOrds = scorer.acceptOrds(acceptDocs);
            KnnResults kr = ExactKnnSearcher.search(scorer, Math.max(1, k), acceptOrds);
            return toTopDocs(kr);
        }
        KnnResults kr = KnnSearch.search(scorer, graphData.graph, k, numCandidates, acceptDocs);
        return toTopDocs(kr);
    }

    private RandomVectorScorer buildApproxScorer(float[] queryVector, RandomVectorScorer rawScorer) {
        int maxOrd = graphData.ordToDoc.length;
        if (graphData.quantization == QuantizationMode.INT8 || graphData.quantization == QuantizationMode.INT4) {
            ScalarQuantizer sq = graphData.scalarQuantizer;
            float[] queryApprox = sq.dequantize(sq.quantize(queryVector));
            return new RandomVectorScorer() {
                @Override
                public float score(int ord) {
                    float[] docApprox = sq.dequantize(graphData.scalarQuantizedByOrd[ord]);
                    return similarity.score(queryApprox, docApprox);
                }

                @Override
                public int maxOrd() {
                    return maxOrd;
                }

                @Override
                public int ordToDoc(int ord) {
                    return graphData.ordToDoc[ord];
                }
            };
        }
        BinaryQuantizer bq = graphData.binaryQuantizer;
        BinaryQuantizer.Quantized queryQuantized = bq.quantize(queryVector);
        boolean dotBased = similarity != VectorSimilarity.L2_NORM;
        int dims = graphData.dims;
        return new RandomVectorScorer() {
            @Override
            public float score(int ord) {
                if (!dotBased) {
                    return rawScorer.score(ord);
                }
                BinaryQuantizer.Quantized docQuantized = new BinaryQuantizer.Quantized(
                    graphData.binaryBitsByOrd[ord], graphData.binaryCentroidDotByOrd[ord],
                    graphData.binaryResidualScaleByOrd[ord], graphData.binaryPopcountByOrd[ord]);
                float approxDot = bq.approximateDotProduct(queryQuantized, docQuantized, dims);
                return similarity.similarityToScore(approxDot, ElementType.FLOAT, dims);
            }

            @Override
            public int maxOrd() {
                return maxOrd;
            }

            @Override
            public int ordToDoc(int ord) {
                return graphData.ordToDoc[ord];
            }
        };
    }

    private static TopDocs toTopDocs(KnnResults kr) {
        ScoreDoc[] scoreDocs = new ScoreDoc[kr.size()];
        for (int i = 0; i < kr.size(); i++) {
            scoreDocs[i] = new ScoreDoc(kr.doc(i), kr.score(i));
        }
        return new TopDocs(new TotalHits(kr.size(), TotalHits.Relation.EQUAL_TO), scoreDocs);
    }
}
