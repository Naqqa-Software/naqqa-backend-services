package com.naqqa.elasticsearch.common.geo;

import com.naqqa.elasticsearch.common.geo.format.WellKnownText;
import com.naqqa.elasticsearch.common.geo.geometry.Geometry;
import com.naqqa.elasticsearch.common.geo.geometry.Point;

import java.util.List;
import java.util.Map;

public final class GeoPointParser {

    private GeoPointParser() {
    }

    @SuppressWarnings("unchecked")
    public static GeoPoint parse(Object value, boolean ignoreZValue) {
        if (value instanceof GeoPoint gp) {
            return gp;
        }
        if (value instanceof Map<?, ?> map) {
            return parseMap((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return parseArray(list, ignoreZValue);
        }
        if (value instanceof String str) {
            return parseString(str.trim(), ignoreZValue);
        }
        throw new IllegalArgumentException("cannot parse geo point from [" + value + "]");
    }

    private static GeoPoint parseMap(Map<String, Object> map) {
        Object lat = map.get("lat");
        Object lon = map.get("lon");
        if (lat == null || lon == null) {
            throw new IllegalArgumentException("geo_point map must contain 'lat' and 'lon'");
        }
        double latitude = toDouble(lat);
        double longitude = toDouble(lon);
        GeoUtils.checkLatitude(latitude);
        GeoUtils.checkLongitude(longitude);
        return new GeoPoint(latitude, longitude);
    }

    private static GeoPoint parseArray(List<?> list, boolean ignoreZValue) {
        if (list.size() < 2 || list.size() > 3) {
            throw new IllegalArgumentException("geo_point array must have 2 or 3 elements [lon, lat, alt?]");
        }
        if (list.size() == 3 && !ignoreZValue) {
            throw new IllegalArgumentException("geo_point array with 3 elements requires ignore_z_value");
        }
        double lon = toDouble(list.get(0));
        double lat = toDouble(list.get(1));
        GeoUtils.checkLatitude(lat);
        GeoUtils.checkLongitude(lon);
        return new GeoPoint(lat, lon);
    }

    private static GeoPoint parseString(String value, boolean ignoreZValue) {
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (upper.startsWith("POINT")) {
            Geometry g = WellKnownText.fromWKT(value);
            if (!(g instanceof Point p)) {
                throw new IllegalArgumentException("expected WKT POINT, got [" + value + "]");
            }
            GeoUtils.checkLatitude(p.getY());
            GeoUtils.checkLongitude(p.getX());
            return new GeoPoint(p.getY(), p.getX());
        }
        if (value.indexOf(',') >= 0) {
            String[] parts = value.split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException("could not parse geo point [" + value + "], expected 'lat,lon'");
            }
            double lat = Double.parseDouble(parts[0].trim());
            double lon = Double.parseDouble(parts[1].trim());
            GeoUtils.checkLatitude(lat);
            GeoUtils.checkLongitude(lon);
            return new GeoPoint(lat, lon);
        }
        if (Geohash.isValid(value)) {
            return Geohash.decode(value);
        }
        throw new IllegalArgumentException("could not parse geo point from [" + value + "]");
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            return Double.parseDouble(s);
        }
        throw new IllegalArgumentException("cannot parse number from [" + o + "]");
    }
}
