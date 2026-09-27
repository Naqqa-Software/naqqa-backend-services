package com.naqqa.elasticsearch.search.vectors.segment.sparse;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SparseVectorSegmentReader implements SparseVectorAccessor {

    private final int maxDoc;
    private final Map<Integer, Map<String, Float>> featuresByDoc;

    private SparseVectorSegmentReader(int maxDoc, Map<Integer, Map<String, Float>> featuresByDoc) {
        this.maxDoc = maxDoc;
        this.featuresByDoc = featuresByDoc;
    }

    public static SparseVectorSegmentReader open(Directory dir, String fileName) throws IOException {
        try (IndexInput in = dir.openInput(fileName, IOContext.READ)) {
            CodecUtil.checksumEntireFile(in);
            CodecUtil.checkHeader(in, SparseVectorSegmentWriter.CODEC_NAME, SparseVectorSegmentWriter.VERSION_CURRENT,
                SparseVectorSegmentWriter.VERSION_CURRENT);
            int maxDoc = in.readVInt();
            int entryCount = in.readVInt();
            Map<Integer, Map<String, Float>> featuresByDoc = new HashMap<>();
            for (int i = 0; i < entryCount; i++) {
                int docId = in.readVInt();
                int featureCount = in.readVInt();
                Map<String, Float> features = new LinkedHashMap<>();
                for (int f = 0; f < featureCount; f++) {
                    String token = in.readString();
                    float weight = Float.intBitsToFloat(in.readInt());
                    features.put(token, weight);
                }
                featuresByDoc.put(docId, features);
            }
            return new SparseVectorSegmentReader(maxDoc, featuresByDoc);
        }
    }

    @Override
    public int maxDoc() {
        return maxDoc;
    }

    @Override
    public boolean hasFeatures(int docId) {
        return featuresByDoc.containsKey(docId);
    }

    @Override
    public Map<String, Float> getFeatures(int docId) {
        return featuresByDoc.getOrDefault(docId, Map.of());
    }
}
