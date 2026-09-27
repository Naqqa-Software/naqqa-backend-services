package com.naqqa.elasticsearch.search.vectors.segment;

import com.naqqa.elasticsearch.codec.vectors.VectorsFormat;
import com.naqqa.elasticsearch.search.vectors.ElementType;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswConfig;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class VectorSegmentWriter {

    private VectorSegmentWriter() {
    }

    public static void write(Directory dir, String fileName, int maxDoc, ElementType elementType,
                              VectorSimilarity similarity, List<VectorEntry> vectors, HnswConfig hnswConfig,
                              QuantizationMode quantization) throws IOException {
        if (elementType != ElementType.FLOAT && quantization != QuantizationMode.NONE) {
            throw new IllegalArgumentException("quantization is only supported for element_type [float]");
        }
        List<VectorEntry> sorted = new ArrayList<>(vectors);
        sorted.sort(Comparator.comparingInt(VectorEntry::docId));
        for (VectorEntry e : sorted) {
            if (e.docId() < 0 || e.docId() >= maxDoc) {
                throw new IllegalArgumentException("docId " + e.docId() + " out of range [0," + maxDoc + ")");
            }
        }

        VectorSegmentCodec.SegmentGraphData data;
        try (IndexOutput out = dir.createOutput(fileName, IOContext.DEFAULT)) {
            if (elementType == ElementType.FLOAT) {
                float[][] vectorsByOrd = new float[sorted.size()][];
                int[] ordToDoc = new int[sorted.size()];
                float[][] vectorsByDoc = new float[maxDoc][];
                for (int i = 0; i < sorted.size(); i++) {
                    VectorEntry e = sorted.get(i);
                    vectorsByOrd[i] = e.floatVector();
                    ordToDoc[i] = e.docId();
                    vectorsByDoc[e.docId()] = e.floatVector();
                }
                int dims = vectorsByOrd.length == 0 ? 0 : vectorsByOrd[0].length;
                data = VectorSegmentCodec.buildFloat(vectorsByOrd, ordToDoc, similarity, hnswConfig, quantization);
                byte[] blob = VectorSegmentCodec.encode(data);
                VectorsFormat.writeFloatVectors(out, dims, vectorsByDoc, blob);
            } else {
                byte[][] vectorsByOrd = new byte[sorted.size()][];
                int[] ordToDoc = new int[sorted.size()];
                byte[][] vectorsByDoc = new byte[maxDoc][];
                for (int i = 0; i < sorted.size(); i++) {
                    VectorEntry e = sorted.get(i);
                    vectorsByOrd[i] = e.byteVector();
                    ordToDoc[i] = e.docId();
                    vectorsByDoc[e.docId()] = e.byteVector();
                }
                int byteLength = vectorsByOrd.length == 0 ? 0 : vectorsByOrd[0].length;
                data = VectorSegmentCodec.buildBytes(vectorsByOrd, ordToDoc, elementType, similarity, hnswConfig);
                byte[] blob = VectorSegmentCodec.encode(data);
                VectorsFormat.writeByteVectors(out, byteLength, vectorsByDoc, blob);
            }
        }
    }
}
