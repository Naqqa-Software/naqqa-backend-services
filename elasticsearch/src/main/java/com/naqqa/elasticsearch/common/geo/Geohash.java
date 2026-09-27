package com.naqqa.elasticsearch.common.geo;

import com.naqqa.elasticsearch.common.geo.geometry.Rectangle;

import java.util.ArrayList;
import java.util.List;

public final class Geohash {

    public static final int PRECISION = 12;

    private static final char[] BASE_32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();
    private static final int[] DECODE = new int[128];

    static {
        java.util.Arrays.fill(DECODE, -1);
        for (int i = 0; i < BASE_32.length; i++) {
            DECODE[BASE_32[i]] = i;
            DECODE[Character.toUpperCase(BASE_32[i])] = i;
        }
    }

    public enum Direction {
        NORTH(0, 1),
        NORTH_EAST(1, 1),
        EAST(1, 0),
        SOUTH_EAST(1, -1),
        SOUTH(0, -1),
        SOUTH_WEST(-1, -1),
        WEST(-1, 0),
        NORTH_WEST(-1, 1);

        private final int dx;
        private final int dy;

        Direction(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }

        public int dx() {
            return dx;
        }

        public int dy() {
            return dy;
        }
    }

    private Geohash() {
    }

    private static void checkLevel(int level) {
        if (level < 1 || level > PRECISION) {
            throw new IllegalArgumentException("Invalid geohash aggregation precision of " + level + ". Must be between 1 and " + PRECISION + ".");
        }
    }

    private static long quantize(double value, double min, double range) {
        long q = (long) Math.floor((value - min) / range * 4294967296.0);
        if (q < 0) {
            return 0;
        }
        return Math.min(q, 0xFFFFFFFFL);
    }

    static long spread(long v) {
        v &= 0xFFFFFFFFL;
        v = (v | (v << 16)) & 0x0000FFFF0000FFFFL;
        v = (v | (v << 8)) & 0x00FF00FF00FF00FFL;
        v = (v | (v << 4)) & 0x0F0F0F0F0F0F0F0FL;
        v = (v | (v << 2)) & 0x3333333333333333L;
        v = (v | (v << 1)) & 0x5555555555555555L;
        return v;
    }

    static long compact(long v) {
        v &= 0x5555555555555555L;
        v = (v ^ (v >>> 1)) & 0x3333333333333333L;
        v = (v ^ (v >>> 2)) & 0x0F0F0F0F0F0F0F0FL;
        v = (v ^ (v >>> 4)) & 0x00FF00FF00FF00FFL;
        v = (v ^ (v >>> 8)) & 0x0000FFFF0000FFFFL;
        v = (v ^ (v >>> 16)) & 0x00000000FFFFFFFFL;
        return v;
    }

    public static long mortonEncode(double lon, double lat) {
        GeoUtils.checkLongitude(lon);
        GeoUtils.checkLatitude(lat);
        long lonBits = quantize(lon, -180.0, 360.0);
        long latBits = quantize(lat, -90.0, 180.0);
        return (spread(lonBits) << 1) | spread(latBits);
    }

    private static long hashBits(double lon, double lat, int level) {
        return mortonEncode(lon, lat) >>> (64 - 5 * level);
    }

    public static String stringEncode(double lon, double lat) {
        return stringEncode(lon, lat, PRECISION);
    }

    public static String stringEncode(double lon, double lat, int level) {
        checkLevel(level);
        return bitsToString(hashBits(lon, lat, level), level);
    }

    public static long longEncode(double lon, double lat, int level) {
        checkLevel(level);
        return (hashBits(lon, lat, level) << 4) | level;
    }

    public static long longEncode(String geohash) {
        int level = geohash.length();
        checkLevel(level);
        return (stringToBits(geohash) << 4) | level;
    }

    public static String stringEncode(long geohashLong) {
        int level = (int) (geohashLong & 15);
        checkLevel(level);
        return bitsToString(geohashLong >>> 4, level);
    }

    public static long mortonEncode(String geohash) {
        int level = geohash.length();
        checkLevel(level);
        return stringToBits(geohash) << (64 - 5 * level);
    }

    private static String bitsToString(long bits, int level) {
        char[] chars = new char[level];
        for (int i = level - 1; i >= 0; i--) {
            chars[i] = BASE_32[(int) (bits & 31)];
            bits >>>= 5;
        }
        return new String(chars);
    }

    private static long stringToBits(String geohash) {
        long bits = 0;
        for (int i = 0; i < geohash.length(); i++) {
            char c = geohash.charAt(i);
            int v = c < 128 ? DECODE[c] : -1;
            if (v < 0) {
                throw new IllegalArgumentException("unsupported symbol [" + c + "] in geohash [" + geohash + "]");
            }
            bits = (bits << 5) | v;
        }
        return bits;
    }

    public static boolean isValid(String geohash) {
        if (geohash == null || geohash.isEmpty() || geohash.length() > PRECISION) {
            return false;
        }
        for (int i = 0; i < geohash.length(); i++) {
            char c = geohash.charAt(i);
            if (c >= 128 || DECODE[c] < 0) {
                return false;
            }
        }
        return true;
    }

    private static int lonBitCount(int level) {
        return (5 * level + 1) / 2;
    }

    private static int latBitCount(int level) {
        return (5 * level) / 2;
    }

    private static long[] cells(String geohash) {
        int level = geohash.length();
        checkLevel(level);
        long morton = stringToBits(geohash) << (64 - 5 * level);
        long lonIdx = compact(morton >>> 1);
        long latIdx = compact(morton);
        int nLon = lonBitCount(level);
        int nLat = latBitCount(level);
        return new long[] {lonIdx >>> (32 - nLon), latIdx >>> (32 - nLat), nLon, nLat, level};
    }

    private static String fromCells(long lonCell, long latCell, int level) {
        int nLon = lonBitCount(level);
        int nLat = latBitCount(level);
        long lonIdx = nLon == 0 ? 0 : lonCell << (32 - nLon);
        long latIdx = nLat == 0 ? 0 : latCell << (32 - nLat);
        long morton = (spread(lonIdx) << 1) | spread(latIdx);
        return bitsToString(morton >>> (64 - 5 * level), level);
    }

    public static Rectangle toBoundingBox(String geohash) {
        long[] c = cells(geohash);
        double lonWidth = 360.0 / (double) (1L << c[2]);
        double latHeight = 180.0 / (double) (1L << c[3]);
        double minLon = -180.0 + c[0] * lonWidth;
        double minLat = -90.0 + c[1] * latHeight;
        double maxLon = c[0] == (1L << c[2]) - 1 ? 180.0 : minLon + lonWidth;
        double maxLat = c[1] == (1L << c[3]) - 1 ? 90.0 : minLat + latHeight;
        return new Rectangle(minLon, maxLon, maxLat, minLat);
    }

    public static Rectangle toBoundingBox(long geohashLong) {
        return toBoundingBox(stringEncode(geohashLong));
    }

    public static GeoPoint decode(String geohash) {
        Rectangle r = toBoundingBox(geohash);
        return new GeoPoint((r.getMinLat() + r.getMaxLat()) / 2.0, (r.getMinLon() + r.getMaxLon()) / 2.0);
    }

    public static double decodeLatitude(String geohash) {
        return decode(geohash).lat();
    }

    public static double decodeLongitude(String geohash) {
        return decode(geohash).lon();
    }

    public static double decodeLatitude(long geohashLong) {
        return decode(stringEncode(geohashLong)).lat();
    }

    public static double decodeLongitude(long geohashLong) {
        return decode(stringEncode(geohashLong)).lon();
    }

    public static String getNeighbor(String geohash, int dx, int dy) {
        long[] c = cells(geohash);
        long lonCells = 1L << c[2];
        long latCells = 1L << c[3];
        long lat = c[1] + dy;
        if (lat < 0 || lat >= latCells) {
            return null;
        }
        long lon = Math.floorMod(c[0] + dx, lonCells);
        return fromCells(lon, lat, (int) c[4]);
    }

    public static String neighbor(String geohash, Direction direction) {
        return getNeighbor(geohash, direction.dx(), direction.dy());
    }

    public static List<String> getNeighbors(String geohash) {
        List<String> out = new ArrayList<>(8);
        Direction[] order = {Direction.NORTH_WEST, Direction.NORTH, Direction.NORTH_EAST, Direction.EAST,
            Direction.SOUTH_EAST, Direction.SOUTH, Direction.SOUTH_WEST, Direction.WEST};
        for (Direction d : order) {
            String n = neighbor(geohash, d);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    public static String[] getSubGeohashes(String geohash) {
        if (geohash.length() >= PRECISION) {
            throw new IllegalArgumentException("geohash [" + geohash + "] is already at maximum precision");
        }
        String[] out = new String[BASE_32.length];
        for (int i = 0; i < BASE_32.length; i++) {
            out[i] = geohash + BASE_32[i];
        }
        return out;
    }
}
