package com.naqqa.elasticsearch.search.vectors;

public interface ByteVectorValues extends VectorValues {

    byte[] vectorValue(int ord);

    @Override
    default ElementType elementType() {
        return ElementType.BYTE;
    }

    static ByteVectorValues of(byte[][] vectors) {
        return new ArrayByteVectorValues(vectors, null, ElementType.BYTE);
    }

    static ByteVectorValues of(byte[][] vectors, int[] ordToDoc) {
        return new ArrayByteVectorValues(vectors, ordToDoc, ElementType.BYTE);
    }

    static ByteVectorValues bits(byte[][] vectors, int[] ordToDoc) {
        return new ArrayByteVectorValues(vectors, ordToDoc, ElementType.BIT);
    }
}
