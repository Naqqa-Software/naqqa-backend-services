package com.naqqa.elasticsearch.common.geo;

public final class GeoEncodingUtils {

    private static final double LAT_SCALE = (0x1L << 32) / 180.0D;
    private static final double LAT_DECODE = 1.0D / LAT_SCALE;
    private static final double LON_SCALE = (0x1L << 32) / 360.0D;
    private static final double LON_DECODE = 1.0D / LON_SCALE;

    private GeoEncodingUtils() {
    }

    public static int encodeLatitude(double latitude) {
        GeoUtils.checkLatitude(latitude);
        if (latitude == 90.0D) {
            latitude = Math.nextDown(latitude);
        }
        return (int) Math.floor(latitude / LAT_DECODE);
    }

    public static int encodeLatitudeCeil(double latitude) {
        GeoUtils.checkLatitude(latitude);
        if (latitude == 90.0D) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(latitude / LAT_DECODE);
    }

    public static int encodeLongitude(double longitude) {
        GeoUtils.checkLongitude(longitude);
        if (longitude == 180.0D) {
            longitude = Math.nextDown(longitude);
        }
        return (int) Math.floor(longitude / LON_DECODE);
    }

    public static int encodeLongitudeCeil(double longitude) {
        GeoUtils.checkLongitude(longitude);
        if (longitude == 180.0D) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.ceil(longitude / LON_DECODE);
    }

    public static double decodeLatitude(int encoded) {
        double result = encoded * LAT_DECODE;
        return Math.min(result, 90.0D);
    }

    public static double decodeLongitude(int encoded) {
        double result = encoded * LON_DECODE;
        return Math.min(result, 180.0D);
    }

    public static long encodePoint(double latitude, double longitude) {
        long lat = encodeLatitude(latitude) & 0xFFFFFFFFL;
        long lon = encodeLongitude(longitude) & 0xFFFFFFFFL;
        return (lat << 32) | lon;
    }

    public static double decodeLatitudeFromPoint(long encoded) {
        return decodeLatitude((int) (encoded >>> 32));
    }

    public static double decodeLongitudeFromPoint(long encoded) {
        return decodeLongitude((int) encoded);
    }
}
