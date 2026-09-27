package com.naqqa.elasticsearch.common.geo;

import com.naqqa.elasticsearch.common.geo.geometry.Point;

public final class GeoPoint {

    private final double lat;
    private final double lon;

    public GeoPoint(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }

    public static GeoPoint fromGeohash(String geohash) {
        return Geohash.decode(geohash);
    }

    public static GeoPoint fromGeohash(long geohashLong) {
        return Geohash.decode(Geohash.stringEncode(geohashLong));
    }

    public static GeoPoint parse(Object value) {
        return GeoPointParser.parse(value, true);
    }

    public static GeoPoint fromPoint(Point point) {
        return new GeoPoint(point.getLat(), point.getLon());
    }

    public double lat() {
        return lat;
    }

    public double lon() {
        return lon;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public String geohash() {
        return Geohash.stringEncode(lon, lat, Geohash.PRECISION);
    }

    public String geohash(int precision) {
        return Geohash.stringEncode(lon, lat, precision);
    }

    public GeoPoint normalized() {
        return GeoUtils.normalizePoint(this);
    }

    public boolean isValid() {
        return GeoUtils.isValidLatitude(lat) && GeoUtils.isValidLongitude(lon);
    }

    public double arcDistance(GeoPoint other) {
        return GeoUtils.arcDistance(lat, lon, other.lat, other.lon);
    }

    public double planeDistance(GeoPoint other) {
        return GeoUtils.planeDistance(lat, lon, other.lat, other.lon);
    }

    public Point toPoint() {
        return new Point(lon, lat);
    }

    public double[] toLonLatArray() {
        return new double[] {lon, lat};
    }

    public String toWKT() {
        return "POINT (" + lon + " " + lat + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof GeoPoint g && Double.compare(lat, g.lat) == 0 && Double.compare(lon, g.lon) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * Double.hashCode(lat) + Double.hashCode(lon);
    }

    @Override
    public String toString() {
        return lat + ", " + lon;
    }
}
