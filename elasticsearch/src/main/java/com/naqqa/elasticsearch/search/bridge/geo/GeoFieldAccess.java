package com.naqqa.elasticsearch.search.bridge.geo;

public final class GeoFieldAccess {

    private GeoFieldAccess() {
    }

    public static long encode(double lat, double lon) {
        return (((long) Float.floatToIntBits((float) lat)) << 32) | (Float.floatToIntBits((float) lon) & 0xFFFFFFFFL);
    }

    public static double decodeLat(long encoded) {
        return Float.intBitsToFloat((int) (encoded >>> 32));
    }

    public static double decodeLon(long encoded) {
        return Float.intBitsToFloat((int) encoded);
    }
}
