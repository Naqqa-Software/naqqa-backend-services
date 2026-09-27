package com.naqqa.elasticsearch.search.vectors.segment;

import com.naqqa.elasticsearch.codec.vectors.VectorsFormat;
import com.naqqa.elasticsearch.search.vectors.ElementType;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;
import com.naqqa.elasticsearch.search.vectors.hnsw.HnswConfig;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public final class VectorSegmentMerger {

    private VectorSegmentMerger() {
    }

    public static void merge(Directory dir, String outFileName, List<VectorSegmentReader> sources,
                              List<int[]> docIdRemapping, int mergedMaxDoc, VectorSimilarity similarity,
                              HnswConfig hnswConfig, QuantizationMode quantization) throws IOException {
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        if (sources.size() != docIdRemapping.size()) {
            throw new IllegalArgumentException("docIdRemapping must have one entry per source");
        }
        ElementType elementType = sources.get(0).elementType();
        for (VectorSegmentReader r : sources) {
            if (r.elementType() != elementType) {
                throw new IllegalArgumentException("all sources must share the same element_type");
            }
        }

        int[] newDocToOrd = new int[mergedMaxDoc];
        Arrays.fill(newDocToOrd, -1);
        int total = 0;
        for (int i = 0; i < sources.size(); i++) {
            VectorSegmentReader src = sources.get(i);
            int[] remap = docIdRemapping.get(i);
            for (int oldDoc = 0; oldDoc < src.maxDoc(); oldDoc++) {
                if (!src.hasVector(oldDoc)) {
                    continue;
                }
                int newDoc = oldDoc < remap.length ? remap[oldDoc] : -1;
                if (newDoc < 0) {
                    continue;
                }
                if (newDocToOrd[newDoc] < 0) {
                    newDocToOrd[newDoc] = total++;
                }
            }
        }

        int[] ordToDoc = new int[total];
        for (int doc = 0; doc < mergedMaxDoc; doc++) {
            if (newDocToOrd[doc] >= 0) {
                ordToDoc[newDocToOrd[doc]] = doc;
            }
        }

        int initializerIdx = selectSafeInitializer(sources, docIdRemapping, hnswConfig);
        int[] oldToNewOrd = null;
        if (initializerIdx >= 0) {
            VectorSegmentReader initializer = sources.get(initializerIdx);
            int[] initRemap = docIdRemapping.get(initializerIdx);
            int initOrdCount = initializer.maxOrdCount();
            oldToNewOrd = new int[initOrdCount];
            for (int ord = 0; ord < initOrdCount; ord++) {
                int oldDoc = initializer.ordToDoc(ord);
                int newDoc = oldDoc < initRemap.length ? initRemap[oldDoc] : -1;
                oldToNewOrd[ord] = newDoc >= 0 ? newDocToOrd[newDoc] : -1;
            }
        }

        VectorSegmentCodec.SegmentGraphData data;
        try (IndexOutput out = dir.createOutput(outFileName, IOContext.DEFAULT)) {
            if (elementType == ElementType.FLOAT) {
                float[][] vectorsByOrd = new float[total][];
                float[][] vectorsByDoc = new float[mergedMaxDoc][];
                collectFloat(sources, docIdRemapping, newDocToOrd, vectorsByOrd, vectorsByDoc);
                data = initializerIdx >= 0
                    ? VectorSegmentCodec.buildFloatFromInitializer(vectorsByOrd, ordToDoc, similarity, hnswConfig,
                        quantization, sources.get(initializerIdx).graph(), oldToNewOrd)
                    : VectorSegmentCodec.buildFloat(vectorsByOrd, ordToDoc, similarity, hnswConfig, quantization);
                byte[] blob = VectorSegmentCodec.encode(data);
                int dims = vectorsByOrd.length == 0 ? sources.get(0).dims() : vectorsByOrd[0].length;
                VectorsFormat.writeFloatVectors(out, dims, vectorsByDoc, blob);
            } else {
                byte[][] vectorsByOrd = new byte[total][];
                byte[][] vectorsByDoc = new byte[mergedMaxDoc][];
                collectBytes(sources, docIdRemapping, newDocToOrd, vectorsByOrd, vectorsByDoc);
                data = initializerIdx >= 0
                    ? VectorSegmentCodec.buildBytesFromInitializer(vectorsByOrd, ordToDoc, elementType, similarity,
                        hnswConfig, sources.get(initializerIdx).graph(), oldToNewOrd)
                    : VectorSegmentCodec.buildBytes(vectorsByOrd, ordToDoc, elementType, similarity, hnswConfig);
                byte[] blob = VectorSegmentCodec.encode(data);
                int byteLength = vectorsByOrd.length == 0 ? sources.get(0).dims() : vectorsByOrd[0].length;
                VectorsFormat.writeByteVectors(out, byteLength, vectorsByDoc, blob);
            }
        }
    }

    private static void collectFloat(List<VectorSegmentReader> sources, List<int[]> docIdRemapping, int[] newDocToOrd,
                                      float[][] vectorsByOrd, float[][] vectorsByDoc) {
        for (int i = 0; i < sources.size(); i++) {
            VectorSegmentReader src = sources.get(i);
            int[] remap = docIdRemapping.get(i);
            for (int oldDoc = 0; oldDoc < src.maxDoc(); oldDoc++) {
                if (!src.hasVector(oldDoc)) {
                    continue;
                }
                int newDoc = oldDoc < remap.length ? remap[oldDoc] : -1;
                if (newDoc < 0) {
                    continue;
                }
                int ord = newDocToOrd[newDoc];
                if (vectorsByOrd[ord] == null) {
                    vectorsByOrd[ord] = src.getVector(oldDoc);
                    vectorsByDoc[newDoc] = vectorsByOrd[ord];
                }
            }
        }
    }

    private static void collectBytes(List<VectorSegmentReader> sources, List<int[]> docIdRemapping, int[] newDocToOrd,
                                      byte[][] vectorsByOrd, byte[][] vectorsByDoc) {
        for (int i = 0; i < sources.size(); i++) {
            VectorSegmentReader src = sources.get(i);
            int[] remap = docIdRemapping.get(i);
            for (int oldDoc = 0; oldDoc < src.maxDoc(); oldDoc++) {
                if (!src.hasVector(oldDoc)) {
                    continue;
                }
                int newDoc = oldDoc < remap.length ? remap[oldDoc] : -1;
                if (newDoc < 0) {
                    continue;
                }
                int ord = newDocToOrd[newDoc];
                if (vectorsByOrd[ord] == null) {
                    vectorsByOrd[ord] = src.getByteVector(oldDoc);
                    vectorsByDoc[newDoc] = vectorsByOrd[ord];
                }
            }
        }
    }

    private static int selectSafeInitializer(List<VectorSegmentReader> sources, List<int[]> docIdRemapping, HnswConfig config) {
        int bestIdx = -1;
        int bestCount = -1;
        for (int i = 0; i < sources.size(); i++) {
            VectorSegmentReader src = sources.get(i);
            if (src.graph() == null || src.maxOrdCount() == 0 || src.graph().maxConn() != config.m()) {
                continue;
            }
            int[] remap = docIdRemapping.get(i);
            if (hasDrops(src, remap)) {
                continue;
            }
            int count = src.maxOrdCount();
            if (count > bestCount) {
                bestCount = count;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static boolean hasDrops(VectorSegmentReader src, int[] remap) {
        for (int oldDoc = 0; oldDoc < src.maxDoc(); oldDoc++) {
            if (src.hasVector(oldDoc) && (oldDoc >= remap.length || remap[oldDoc] < 0)) {
                return true;
            }
        }
        return false;
    }
}
