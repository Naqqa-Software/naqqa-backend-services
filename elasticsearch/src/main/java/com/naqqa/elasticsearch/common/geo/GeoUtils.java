package com.naqqa.elasticsearch.common.geo;

import com.naqqa.elasticsearch.common.geo.geometry.Rectangle;
import com.naqqa.elasticsearch.common.geo.shape.Relation;

public final class GeoUtils {

    public static final double EARTH_MEAN_RADIUS = 6371008.7714D;
    public static final double EARTH_SEMI_MAJOR_AXIS = 6378137.0;
    public static final double EARTH_SEMI_MINOR_AXIS = 6356752.314245;
    public static final double EARTH_EQUATOR = 2 * Math.PI * EARTH_SEMI_MAJOR_AXIS;
    public static final double EARTH_POLAR_DISTANCE = Math.PI * EARTH_SEMI_MINOR_AXIS;

    public static final double MIN_LAT_INCL = -90.0D;
    public static final double MAX_LAT_INCL = 90.0D;
    public static final double MIN_LON_INCL = -180.0D;
    public static final double MAX_LON_INCL = 180.0D;

    public static final double MIN_LAT_RADIANS = Math.toRadians(MIN_LAT_INCL);
    public static final double MAX_LAT_RADIANS = Math.toRadians(MAX_LAT_INCL);
    public static final double MIN_LON_RADIANS = Math.toRadians(MIN_LON_INCL);
    public static final double MAX_LON_RADIANS = Math.toRadians(MAX_LON_INCL);

    public static final double AXISLAT_ERROR = Math.toDegrees(0.1D / EARTH_MEAN_RADIUS);
    private static final double CIRCLE_ERROR_METERS = 7E-2;

    private GeoUtils() {
    }

    public static boolean isValidLatitude(double latitude) {
        return !Double.isNaN(latitude) && !Double.isInfinite(latitude) && latitude >= MIN_LAT_INCL && latitude <= MAX_LAT_INCL;
    }

    public static boolean isValidLongitude(double longitude) {
        return !Double.isNaN(longitude) && !Double.isInfinite(longitude) && longitude >= MIN_LON_INCL && longitude <= MAX_LON_INCL;
    }

    public static void checkLatitude(double latitude) {
        if (!isValidLatitude(latitude)) {
            throw new IllegalArgumentException("invalid latitude " + latitude + "; must be between -90.0 and 90.0");
        }
    }

    public static void checkLongitude(double longitude) {
        if (!isValidLongitude(longitude)) {
            throw new IllegalArgumentException("invalid longitude " + longitude + "; must be between -180.0 and 180.0");
        }
    }

    private static double centeredModulus(double dividend, double divisor) {
        double rtn = dividend % divisor;
        if (rtn <= 0) {
            rtn += divisor;
        }
        if (rtn > divisor / 2) {
            rtn -= divisor;
        }
        return rtn;
    }

    public static double normalizeLon(double lon) {
        if (lon > 180d || lon <= -180d) {
            lon = centeredModulus(lon, 360);
        }
        return lon + 0d;
    }

    public static double normalizeLat(double lat) {
        if (lat > 90d || lat < -90d) {
            lat = centeredModulus(lat, 360);
            if (lat < -90) {
                lat = -180 - lat;
            } else if (lat > 90) {
                lat = 180 - lat;
            }
        }
        return lat + 0d;
    }

    public static GeoPoint normalizePoint(GeoPoint point) {
        return normalizePoint(point, true, true);
    }

    public static GeoPoint normalizePoint(GeoPoint point, boolean normLat, boolean normLon) {
        double[] pt = {point.lon(), point.lat()};
        normalizePoint(pt, normLon, normLat);
        return new GeoPoint(pt[1], pt[0]);
    }

    public static void normalizePoint(double[] lonLat, boolean normLon, boolean normLat) {
        if ((normLat || normLon) && (!isValidLatitude(lonLat[1]) || !isValidLongitude(lonLat[0]))) {
            if (normLat) {
                lonLat[1] = centeredModulus(lonLat[1], 360);
                boolean shift = true;
                if (lonLat[1] < -90) {
                    lonLat[1] = -180 - lonLat[1];
                } else if (lonLat[1] > 90) {
                    lonLat[1] = 180 - lonLat[1];
                } else {
                    shift = false;
                }
                if (shift) {
                    if (normLon) {
                        lonLat[0] += 180;
                    } else {
                        lonLat[0] += normalizeLon(lonLat[0]) > 0 ? -180 : 180;
                    }
                }
            }
            if (normLon) {
                lonLat[0] = centeredModulus(lonLat[0], 360);
            }
        }
        lonLat[0] = lonLat[0] + 0d;
        lonLat[1] = lonLat[1] + 0d;
    }

    public static double arcDistance(double lat1, double lon1, double lat2, double lon2) {
        return EARTH_MEAN_RADIUS * haversinAngle(lat1, lon1, lat2, lon2);
    }

    public static double haversinAngle(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double h = sinLat * sinLat + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLon * sinLon;
        h = Math.min(1.0, Math.max(0.0, h));
        return 2 * Math.asin(Math.sqrt(h));
    }

    public static double planeDistance(double lat1, double lon1, double lat2, double lon2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat2 + lat1) / 2.0));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * EARTH_MEAN_RADIUS;
    }

    public static double cartesianDistance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static Rectangle circleToBBox(double centerLat, double centerLon, double radiusMeters) {
        double radLat = Math.toRadians(centerLat);
        double radLon = Math.toRadians(centerLon);
        double radDistance = (radiusMeters + CIRCLE_ERROR_METERS) / EARTH_MEAN_RADIUS;
        double minLat = radLat - radDistance;
        double maxLat = radLat + radDistance;
        double minLon;
        double maxLon;
        if (minLat > MIN_LAT_RADIANS && maxLat < MAX_LAT_RADIANS) {
            double deltaLon = Math.asin(Math.min(1.0, Math.sin(radDistance) / Math.cos(radLat)));
            minLon = radLon - deltaLon;
            if (minLon < MIN_LON_RADIANS) {
                minLon += 2d * Math.PI;
            }
            maxLon = radLon + deltaLon;
            if (maxLon > MAX_LON_RADIANS) {
                maxLon -= 2d * Math.PI;
            }
        } else {
            minLat = Math.max(minLat, MIN_LAT_RADIANS);
            maxLat = Math.min(maxLat, MAX_LAT_RADIANS);
            minLon = MIN_LON_RADIANS;
            maxLon = MAX_LON_RADIANS;
        }
        return new Rectangle(Math.toDegrees(minLon), Math.toDegrees(maxLon), Math.toDegrees(maxLat), Math.toDegrees(minLat));
    }

    public static double axisLat(double centerLat, double radiusMeters) {
        double l1 = Math.toRadians(centerLat);
        double r = (radiusMeters + CIRCLE_ERROR_METERS) / EARTH_MEAN_RADIUS;
        if (Math.abs(l1) + r >= MAX_LAT_RADIANS) {
            return centerLat >= 0 ? MAX_LAT_INCL : MIN_LAT_INCL;
        }
        double s = Math.sin(l1) / Math.cos(r);
        s = Math.max(-1.0, Math.min(1.0, s));
        return Math.toDegrees(Math.asin(s));
    }

    public static Relation relateBoxToCircle(double minLat, double maxLat, double minLon, double maxLon,
                                             double lat, double lon, double radiusMeters) {
        if (minLon > maxLon) {
            Relation left = relateBoxToCircle(minLat, maxLat, minLon, MAX_LON_INCL, lat, lon, radiusMeters);
            Relation right = relateBoxToCircle(minLat, maxLat, MIN_LON_INCL, maxLon, lat, lon, radiusMeters);
            if (left == right) {
                return left;
            }
            return Relation.CELL_CROSSES_QUERY;
        }
        return relateBoxToCircle(minLat, maxLat, minLon, maxLon, lat, lon, radiusMeters, axisLat(lat, radiusMeters));
    }

    public static Relation relateBoxToCircle(double minLat, double maxLat, double minLon, double maxLon,
                                             double lat, double lon, double radiusMeters, double axisLat) {
        if (minLon > maxLon) {
            throw new IllegalArgumentException("box crosses the dateline");
        }
        if (lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon) {
            if (within90LonDegrees(lon, minLon, maxLon) && allCornersWithin(minLat, maxLat, minLon, maxLon, lat, lon, radiusMeters)) {
                return Relation.CELL_INSIDE_QUERY;
            }
            return Relation.CELL_CROSSES_QUERY;
        }
        if ((lon < minLon || lon > maxLon) && (axisLat + AXISLAT_ERROR < minLat || axisLat - AXISLAT_ERROR > maxLat)) {
            if (arcDistance(lat, lon, minLat, minLon) > radiusMeters
                && arcDistance(lat, lon, minLat, maxLon) > radiusMeters
                && arcDistance(lat, lon, maxLat, minLon) > radiusMeters
                && arcDistance(lat, lon, maxLat, maxLon) > radiusMeters) {
                return Relation.CELL_OUTSIDE_QUERY;
            }
        }
        if (lon >= minLon && lon <= maxLon) {
            double closestLat = lat < minLat ? minLat : maxLat;
            if (arcDistance(lat, lon, closestLat, lon) > radiusMeters) {
                return Relation.CELL_OUTSIDE_QUERY;
            }
        }
        if (within90LonDegrees(lon, minLon, maxLon) && allCornersWithin(minLat, maxLat, minLon, maxLon, lat, lon, radiusMeters)) {
            return Relation.CELL_INSIDE_QUERY;
        }
        return Relation.CELL_CROSSES_QUERY;
    }

    private static boolean allCornersWithin(double minLat, double maxLat, double minLon, double maxLon, double lat, double lon, double r) {
        return arcDistance(lat, lon, minLat, minLon) <= r
            && arcDistance(lat, lon, minLat, maxLon) <= r
            && arcDistance(lat, lon, maxLat, minLon) <= r
            && arcDistance(lat, lon, maxLat, maxLon) <= r;
    }

    private static boolean within90LonDegrees(double lon, double minLon, double maxLon) {
        if (maxLon <= lon - 180) {
            lon -= 360;
        } else if (minLon >= lon + 180) {
            lon += 360;
        }
        return maxLon <= lon + 90 && minLon >= lon - 90;
    }

    public static Relation relateBoxToCartesianCircle(double minX, double maxX, double minY, double maxY,
                                                      double cx, double cy, double radius) {
        double nx = Math.max(minX, Math.min(cx, maxX));
        double ny = Math.max(minY, Math.min(cy, maxY));
        double dx = nx - cx;
        double dy = ny - cy;
        if (dx * dx + dy * dy > radius * radius) {
            return Relation.CELL_OUTSIDE_QUERY;
        }
        double fx = Math.max(Math.abs(minX - cx), Math.abs(maxX - cx));
        double fy = Math.max(Math.abs(minY - cy), Math.abs(maxY - cy));
        if (fx * fx + fy * fy <= radius * radius) {
            return Relation.CELL_INSIDE_QUERY;
        }
        return Relation.CELL_CROSSES_QUERY;
    }

    public static double geoHashCellWidth(int level) {
        int lonBits = (5 * level + 1) / 2;
        return EARTH_EQUATOR / (double) (1L << lonBits);
    }

    public static double geoHashCellHeight(int level) {
        int latBits = (5 * level) / 2;
        return EARTH_POLAR_DISTANCE / (double) (1L << latBits);
    }

    public static int geoHashLevelsForPrecision(double meters) {
        if (meters <= 0) {
            return Geohash.PRECISION;
        }
        for (int level = 1; level <= Geohash.PRECISION; level++) {
            double w = geoHashCellWidth(level);
            double h = geoHashCellHeight(level);
            if (Math.sqrt(w * w + h * h) <= meters) {
                return level;
            }
        }
        return Geohash.PRECISION;
    }
}
