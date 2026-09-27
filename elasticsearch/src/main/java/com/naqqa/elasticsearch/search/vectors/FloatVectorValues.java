package com.naqqa.elasticsearch.search.vectors;

public interface FloatVectorValues extends VectorValues {

    float[] vectorValue(int ord);

    @Override
    default ElementType elementType() {
        return ElementType.FLOAT;
    }

    static FloatVectorValues of(float[][] vectors) {
        return new ArrayFloatVectorValues(vectors, null);
    }

    static FloatVectorValues of(float[][] vectors, int[] ordToDoc) {
        return new ArrayFloatVectorValues(vectors, ordToDoc);
    }
}
