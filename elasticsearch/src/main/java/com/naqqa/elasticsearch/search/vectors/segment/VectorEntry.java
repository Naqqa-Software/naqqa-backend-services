package com.naqqa.elasticsearch.search.vectors.segment;

public final class VectorEntry {

    private final int docId;
    private final float[] floatVector;
    private final byte[] byteVector;

    private VectorEntry(int docId, float[] floatVector, byte[] byteVector) {
        this.docId = docId;
        this.floatVector = floatVector;
        this.byteVector = byteVector;
    }

    public static VectorEntry ofFloat(int docId, float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector must not be null");
        }
        return new VectorEntry(docId, vector, null);
    }

    public static VectorEntry ofBytes(int docId, byte[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector must not be null");
        }
        return new VectorEntry(docId, null, vector);
    }

    public int docId() {
        return docId;
    }

    public float[] floatVector() {
        return floatVector;
    }

    public byte[] byteVector() {
        return byteVector;
    }
}
