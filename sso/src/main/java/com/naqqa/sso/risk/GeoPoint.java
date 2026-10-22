package com.naqqa.sso.risk;

public record GeoPoint(double latitude, double longitude) {
    public double distanceKm(GeoPoint other) {
        double r = 6371.0088;
        double p1 = Math.toRadians(latitude);
        double p2 = Math.toRadians(other.latitude);
        double dp = p2 - p1;
        double dl = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
