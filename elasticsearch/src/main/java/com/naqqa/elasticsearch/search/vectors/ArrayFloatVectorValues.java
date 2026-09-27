package com.naqqa.elasticsearch.search.vectors;

public final class ArrayFloatVectorValues implements FloatVectorValues {

    private final float[][] vectors;
    private final int[] ordToDoc;
    private final int dimension;

    public ArrayFloatVectorValues(float[][] vectors, int[] ordToDoc) {
        this(vectors, ordToDoc, vectors.length == 0 ? 0 : vectors[0].length);
    }

    public ArrayFloatVectorValues(float[][] vectors, int[] ordToDoc, int dimension) {
        if (ordToDoc != null && ordToDoc.length != vectors.length) {
            throw new IllegalArgumentException("ordToDoc length " + ordToDoc.length + " != vector count " + vectors.length);
        }
        for (float[] v : vectors) {
            if (v.length != dimension) {
                throw new IllegalArgumentException("vector dimension " + v.length + " != " + dimension);
            }
        }
        this.vectors = vectors;
        this.ordToDoc = ordToDoc;
        this.dimension = dimension;
    }

    @Override
    public float[] vectorValue(int ord) {
        return vectors[ord];
    }

    @Override
    public int size() {
        return vectors.length;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public int ordToDoc(int ord) {
        return ordToDoc == null ? ord : ordToDoc[ord];
    }

    public float[][] vectors() {
        return vectors;
    }

    public int[] ordToDocArray() {
        return ordToDoc;
    }
}
