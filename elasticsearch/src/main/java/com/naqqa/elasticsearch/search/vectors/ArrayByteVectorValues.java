package com.naqqa.elasticsearch.search.vectors;

public final class ArrayByteVectorValues implements ByteVectorValues {

    private final byte[][] vectors;
    private final int[] ordToDoc;
    private final int dimension;
    private final ElementType elementType;

    public ArrayByteVectorValues(byte[][] vectors, int[] ordToDoc, ElementType elementType) {
        this(vectors, ordToDoc, elementType, vectors.length == 0 ? 0 : (elementType == ElementType.BIT ? vectors[0].length * 8 : vectors[0].length));
    }

    public ArrayByteVectorValues(byte[][] vectors, int[] ordToDoc, ElementType elementType, int dimension) {
        if (elementType == ElementType.FLOAT) {
            throw new IllegalArgumentException("byte vector values cannot have element_type float");
        }
        if (ordToDoc != null && ordToDoc.length != vectors.length) {
            throw new IllegalArgumentException("ordToDoc length " + ordToDoc.length + " != vector count " + vectors.length);
        }
        int byteLength = elementType == ElementType.BIT ? (dimension + 7) / 8 : dimension;
        for (byte[] v : vectors) {
            if (v.length != byteLength) {
                throw new IllegalArgumentException("vector byte length " + v.length + " != " + byteLength);
            }
        }
        this.vectors = vectors;
        this.ordToDoc = ordToDoc;
        this.dimension = dimension;
        this.elementType = elementType;
    }

    @Override
    public byte[] vectorValue(int ord) {
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
    public ElementType elementType() {
        return elementType;
    }

    @Override
    public int ordToDoc(int ord) {
        return ordToDoc == null ? ord : ordToDoc[ord];
    }

    public byte[][] vectors() {
        return vectors;
    }

    public int[] ordToDocArray() {
        return ordToDoc;
    }
}
