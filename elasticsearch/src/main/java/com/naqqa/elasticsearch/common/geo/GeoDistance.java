package com.naqqa.elasticsearch.common.geo;

import java.util.Locale;

public enum GeoDistance {
    PLANE,
    ARC;

    public static GeoDistance fromString(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "plane" -> PLANE;
            case "arc" -> ARC;
            default -> throw new IllegalArgumentException("No geo distance for [" + name + "]");
        };
    }

    public double calculate(double sourceLatitude, double sourceLongitude, double targetLatitude, double targetLongitude, DistanceUnit unit) {
        double meters = this == PLANE
            ? GeoUtils.planeDistance(sourceLatitude, sourceLongitude, targetLatitude, targetLongitude)
            : GeoUtils.arcDistance(sourceLatitude, sourceLongitude, targetLatitude, targetLongitude);
        return unit.fromMeters(meters);
    }

    public double calculate(GeoPoint source, GeoPoint target, DistanceUnit unit) {
        return calculate(source.lat(), source.lon(), target.lat(), target.lon(), unit);
    }
}
