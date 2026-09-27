package com.naqqa.elasticsearch.common.geo;

import com.naqqa.elasticsearch.common.geo.geometry.Rectangle;

public final class GeoTileUtils {

    public static final int MAX_ZOOM = 29;
    public static final double LATITUDE_MASK = 85.0511287798066;
    public static final double NORMALIZED_LATITUDE_MASK = GeoEncodingUtils.decodeLatitude(GeoEncodingUtils.encodeLatitude(LATITUDE_MASK));
    public static final double NORMALIZED_NEGATIVE_LATITUDE_MASK = GeoEncodingUtils.decodeLatitude(GeoEncodingUtils.encodeLatitude(-LATITUDE_MASK));
    private static final int ZOOM_SHIFT = MAX_ZOOM * 2;
    private static final long X_Y_VALUE_MASK = (1L << MAX_ZOOM) - 1;

    private GeoTileUtils() {
    }

    public static int checkPrecisionRange(int precision) {
        if (precision < 0 || precision > MAX_ZOOM) {
            throw new IllegalArgumentException("Invalid geotile_grid precision of " + precision + ". Must be between 0 and " + MAX_ZOOM + ".");
        }
        return precision;
    }

    public static int getXTile(double longitude, long tiles) {
        long xTile = (long) Math.floor((GeoUtils.normalizeLon(longitude) + 180) / 360 * tiles);
        if (xTile < 0) {
            return 0;
        }
        if (xTile >= tiles) {
            return (int) (tiles - 1);
        }
        return (int) xTile;
    }

    public static int getYTile(double latitude, long tiles) {
        double lat = Math.max(-LATITUDE_MASK, Math.min(LATITUDE_MASK, latitude));
        double latSin = Math.sin(Math.toRadians(lat));
        long yTile = (long) Math.floor((0.5 - (Math.log((1 + latSin) / (1 - latSin)) / (4 * Math.PI))) * tiles);
        if (yTile < 0) {
            return 0;
        }
        if (yTile >= tiles) {
            return (int) (tiles - 1);
        }
        return (int) yTile;
    }

    public static long longEncode(double longitude, double latitude, int precision) {
        long tiles = 1L << checkPrecisionRange(precision);
        long xTile = getXTile(longitude, tiles);
        long yTile = getYTile(latitude, tiles);
        return longEncodeTiles(precision, xTile, yTile);
    }

    public static long longEncodeTiles(int precision, long xTile, long yTile) {
        validateZXY(precision, xTile, yTile);
        return ((long) precision << ZOOM_SHIFT) | (xTile << MAX_ZOOM) | yTile;
    }

    public static long longEncode(String hashAsString) {
        int[] parsed = parseHash(hashAsString);
        return longEncodeTiles(parsed[0], parsed[1], parsed[2]);
    }

    public static int[] parseHash(long hash) {
        int zoom = (int) (hash >>> ZOOM_SHIFT);
        int xTile = (int) ((hash >>> MAX_ZOOM) & X_Y_VALUE_MASK);
        int yTile = (int) (hash & X_Y_VALUE_MASK);
        return new int[] {zoom, xTile, yTile};
    }

    public static int[] parseHash(String hashAsString) {
        String[] parts = hashAsString.split("/", 4);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid geotile_grid hash string of " + hashAsString + ". Must be three integers in a form \"zoom/x/y\".");
        }
        try {
            int zoom = Integer.parseInt(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            validateZXY(zoom, x, y);
            return new int[] {zoom, x, y};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid geotile_grid hash string of " + hashAsString + ". Must be three integers in a form \"zoom/x/y\".", e);
        }
    }

    public static String stringEncode(long hash) {
        int[] res = parseHash(hash);
        validateZXY(res[0], res[1], res[2]);
        return res[0] + "/" + res[1] + "/" + res[2];
    }

    public static String stringEncode(double longitude, double latitude, int precision) {
        return stringEncode(longEncode(longitude, latitude, precision));
    }

    private static long validateZXY(int zoom, long xTile, long yTile) {
        long tiles = 1L << checkPrecisionRange(zoom);
        if (xTile < 0 || yTile < 0 || xTile >= tiles || yTile >= tiles) {
            throw new IllegalArgumentException("Zoom/X/Y combination is not valid: " + zoom + "/" + xTile + "/" + yTile);
        }
        return tiles;
    }

    public static GeoPoint hashToGeoPoint(long hash) {
        int[] res = parseHash(hash);
        return zxyToGeoPoint(res[0], res[1], res[2]);
    }

    public static GeoPoint keyToGeoPoint(String hashAsString) {
        int[] res = parseHash(hashAsString);
        return zxyToGeoPoint(res[0], res[1], res[2]);
    }

    private static GeoPoint zxyToGeoPoint(int zoom, int xTile, int yTile) {
        double tiles = validateZXY(zoom, xTile, yTile);
        double n = Math.PI - (2.0 * Math.PI * (yTile + 0.5)) / tiles;
        double lat = Math.toDegrees(Math.atan(Math.sinh(n)));
        double lon = ((xTile + 0.5) / tiles * 360.0) - 180;
        return new GeoPoint(lat, lon);
    }

    public static Rectangle toBoundingBox(long hash) {
        int[] res = parseHash(hash);
        return toBoundingBox(res[1], res[2], res[0]);
    }

    public static Rectangle toBoundingBox(String hash) {
        int[] res = parseHash(hash);
        return toBoundingBox(res[1], res[2], res[0]);
    }

    public static Rectangle toBoundingBox(int xTile, int yTile, int precision) {
        double tiles = validateZXY(precision, xTile, yTile);
        double minN = Math.PI - (2.0 * Math.PI * (yTile + 1)) / tiles;
        double maxN = Math.PI - (2.0 * Math.PI * yTile) / tiles;
        double minY = Math.toDegrees(Math.atan(Math.sinh(minN)));
        double minX = (xTile / tiles * 360.0) - 180;
        double maxY = Math.toDegrees(Math.atan(Math.sinh(maxN)));
        double maxX = ((xTile + 1) / tiles * 360.0) - 180;
        return new Rectangle(minX, maxX, maxY, minY);
    }

    public static double tileToLon(double xTile, double tiles) {
        return (xTile / tiles * 360.0) - 180;
    }

    public static double tileToLat(double yTile, double tiles) {
        double n = Math.PI - (2.0 * Math.PI * yTile) / tiles;
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }
}
