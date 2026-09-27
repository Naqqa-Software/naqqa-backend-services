package com.naqqa.elasticsearch.search.vectors;

public interface VectorUtilSupport {

    String name();

    float dotProduct(float[] a, float[] b);

    float cosine(float[] a, float[] b);

    float squareDistance(float[] a, float[] b);

    float l1Distance(float[] a, float[] b);

    int dotProduct(byte[] a, byte[] b);

    float cosine(byte[] a, byte[] b);

    int squareDistance(byte[] a, byte[] b);

    int l1Distance(byte[] a, byte[] b);

    int int4DotProductPacked(byte[] unpacked, byte[] packed);

    int int4SquareDistancePacked(byte[] unpacked, byte[] packed);

    long xorBitCount(byte[] a, byte[] b);

    long int4BitDotProduct(byte[] query, byte[] binary);
}
