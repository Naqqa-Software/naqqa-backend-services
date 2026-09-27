package com.naqqa.elasticsearch.search.vectors.query;

import com.naqqa.elasticsearch.search.vectors.ElementType;
import com.naqqa.elasticsearch.search.vectors.VectorScriptFunctions;
import com.naqqa.elasticsearch.search.vectors.segment.VectorSegmentAccessor;

public final class ScriptScoreVectorFunctions {

    private final VectorSegmentAccessor accessor;

    public ScriptScoreVectorFunctions(VectorSegmentAccessor accessor) {
        this.accessor = accessor;
    }

    public double cosineSimilarity(float[] queryVector, int docId) {
        return VectorScriptFunctions.cosineSimilarity(queryVector, requireFloatVector(docId));
    }

    public double dotProduct(float[] queryVector, int docId) {
        return VectorScriptFunctions.dotProduct(queryVector, requireFloatVector(docId));
    }

    public double l1norm(float[] queryVector, int docId) {
        return VectorScriptFunctions.l1norm(queryVector, requireFloatVector(docId));
    }

    public double l2norm(float[] queryVector, int docId) {
        return VectorScriptFunctions.l2norm(queryVector, requireFloatVector(docId));
    }

    public double hamming(byte[] queryVector, int docId) {
        return VectorScriptFunctions.hamming(queryVector, requireByteVector(docId));
    }

    public double dotProductBits(float[] queryVector, int docId) {
        if (accessor.elementType() != ElementType.BIT) {
            throw new IllegalStateException("field is not a bit vector");
        }
        return VectorScriptFunctions.dotProductBits(queryVector, requireByteVector(docId));
    }

    private float[] requireFloatVector(int docId) {
        if (!accessor.hasVector(docId)) {
            throw new IllegalStateException("no vector for doc " + docId);
        }
        float[] v = accessor.getVector(docId);
        if (v == null) {
            throw new IllegalStateException("field element_type is not float for doc " + docId);
        }
        return v;
    }

    private byte[] requireByteVector(int docId) {
        if (!accessor.hasVector(docId)) {
            throw new IllegalStateException("no vector for doc " + docId);
        }
        byte[] v = accessor.getByteVector(docId);
        if (v == null) {
            throw new IllegalStateException("field element_type is not byte/bit for doc " + docId);
        }
        return v;
    }
}
