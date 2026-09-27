package com.naqqa.elasticsearch.script;

import java.util.List;
import java.util.Map;

public record GeoPoint(double lat, double lon) {

    public static final double EARTH_MEAN_RADIUS_METERS = 6371008.7714;

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public double arcDistance(double otherLat, double otherLon) {
        return haversineMeters(lat, lon, otherLat, otherLon);
    }

    public double arcDistance(GeoPoint other) {
        return haversineMeters(lat, lon, other.lat, other.lon);
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
        return EARTH_MEAN_RADIUS_METERS * c;
    }

    public static GeoPoint parse(Object value) {
        if (value instanceof GeoPoint gp) {
            return gp;
        }
        if (value instanceof Map<?, ?> m) {
            Object lat = m.get("lat");
            Object lon = m.get("lon");
            if (lat == null || lon == null) {
                throw new IllegalArgumentException("geo point map must contain [lat] and [lon]: " + m);
            }
            return new GeoPoint(toDouble(lat), toDouble(lon));
        }
        if (value instanceof List<?> l) {
            if (l.size() < 2) {
                throw new IllegalArgumentException("geo point array must contain [lon, lat]: " + l);
            }
            return new GeoPoint(toDouble(l.get(1)), toDouble(l.get(0)));
        }
        if (value instanceof double[] d && d.length >= 2) {
            return new GeoPoint(d[1], d[0]);
        }
        if (value instanceof CharSequence cs) {
            String s = cs.toString().trim();
            if (s.toUpperCase().startsWith("POINT")) {
                int open = s.indexOf('(');
                int close = s.lastIndexOf(')');
                String[] parts = s.substring(open + 1, close).trim().split("\\s+");
                return new GeoPoint(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]));
            }
            int comma = s.indexOf(',');
            if (comma < 0) {
                return decodeGeohash(s);
            }
            return new GeoPoint(Double.parseDouble(s.substring(0, comma).trim()), Double.parseDouble(s.substring(comma + 1).trim()));
        }
        throw new IllegalArgumentException("cannot parse geo point from [" + value + "]");
    }

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    public static GeoPoint decodeGeohash(String hash) {
        double minLat = -90, maxLat = 90, minLon = -180, maxLon = 180;
        boolean even = true;
        for (int i = 0; i < hash.length(); i++) {
            int cd = BASE32.indexOf(Character.toLowerCase(hash.charAt(i)));
            if (cd < 0) {
                throw new IllegalArgumentException("unsupported symbol [" + hash.charAt(i) + "] in geohash [" + hash + "]");
            }
            for (int mask = 16; mask > 0; mask >>= 1) {
                if (even) {
                    double mid = (minLon + maxLon) / 2;
                    if ((cd & mask) != 0) {
                        minLon = mid;
                    } else {
                        maxLon = mid;
                    }
                } else {
                    double mid = (minLat + maxLat) / 2;
                    if ((cd & mask) != 0) {
                        minLat = mid;
                    } else {
                        maxLat = mid;
                    }
                }
                even = !even;
            }
        }
        return new GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2);
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(o.toString());
    }

    @Override
    public String toString() {
        return lat + ", " + lon;
    }
}
