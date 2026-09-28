package com.naqqa.elasticsearch.bench.dataset;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public final class TaxiLikeDataset {

    public record TaxiTrip(String id, long pickupMillis, long dropoffMillis, double pickupLat, double pickupLon,
                            double dropoffLat, double dropoffLon, int passengerCount, double tripDistance,
                            double fareAmount, String paymentType, String vendor) {
    }

    public static final double MIN_LAT = 40.49;
    public static final double MAX_LAT = 40.92;
    public static final double MIN_LON = -74.26;
    public static final double MAX_LON = -73.68;

    private static final String[] PAYMENT_TYPES = {"card", "cash", "no_charge", "dispute"};
    private static final String[] VENDORS = {"VTS", "CMT", "DDS"};

    private final long seed;
    private final long baseTimestampMillis;
    private final long timestampRangeMillis;

    public TaxiLikeDataset(long seed) {
        this.seed = seed;
        this.baseTimestampMillis = Instant.parse("2023-01-01T00:00:00Z").toEpochMilli();
        this.timestampRangeMillis = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli() - baseTimestampMillis;
    }

    public TaxiTrip trip(long index) {
        Random rnd = new Random(WikiLikeDataset.mix(seed ^ 0x51ED270B4C2A11L, index));
        long pickupMillis = baseTimestampMillis + (long) (rnd.nextDouble() * timestampRangeMillis);
        double pickupLat = MIN_LAT + rnd.nextDouble() * (MAX_LAT - MIN_LAT);
        double pickupLon = MIN_LON + rnd.nextDouble() * (MAX_LON - MIN_LON);
        double angle = rnd.nextDouble() * 2 * Math.PI;
        double radiusDegrees = Math.pow(rnd.nextDouble(), 1.5) * 0.12;
        double dropoffLat = clamp(pickupLat + Math.sin(angle) * radiusDegrees, MIN_LAT, MAX_LAT);
        double dropoffLon = clamp(pickupLon + Math.cos(angle) * radiusDegrees, MIN_LON, MAX_LON);
        double tripDistance = round2(haversineMiles(pickupLat, pickupLon, dropoffLat, dropoffLon) * (1.0 + rnd.nextDouble() * 0.4));
        int durationMinutes = Math.max(2, (int) Math.round(tripDistance * 3.2 + rnd.nextDouble() * 8));
        long dropoffMillis = pickupMillis + durationMinutes * 60_000L;
        int passengerCount = 1 + rnd.nextInt(6);
        double fareAmount = round2(2.5 + tripDistance * 2.5 + durationMinutes * 0.35 + rnd.nextDouble() * 3.0);
        String paymentType = PAYMENT_TYPES[rnd.nextInt(PAYMENT_TYPES.length)];
        String vendor = VENDORS[rnd.nextInt(VENDORS.length)];
        return new TaxiTrip("taxi-" + index, pickupMillis, dropoffMillis, round6(pickupLat), round6(pickupLon),
            round6(dropoffLat), round6(dropoffLon), passengerCount, tripDistance, fareAmount, paymentType, vendor);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    public static double haversineMiles(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMiles = 3958.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMiles * c;
    }

    public Map<String, Object> toSource(TaxiTrip trip) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pickup_datetime", Instant.ofEpochMilli(trip.pickupMillis()).toString());
        m.put("dropoff_datetime", Instant.ofEpochMilli(trip.dropoffMillis()).toString());
        m.put("pickup_location", Map.of("lat", trip.pickupLat(), "lon", trip.pickupLon()));
        m.put("dropoff_location", Map.of("lat", trip.dropoffLat(), "lon", trip.dropoffLon()));
        m.put("passenger_count", trip.passengerCount());
        m.put("trip_distance", trip.tripDistance());
        m.put("fare_amount", trip.fareAmount());
        m.put("payment_type", trip.paymentType());
        m.put("vendor", trip.vendor());
        return m;
    }

    public static Map<String, Object> mapping(int shards) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pickup_datetime", Map.of("type", "date"));
        properties.put("dropoff_datetime", Map.of("type", "date"));
        properties.put("pickup_location", Map.of("type", "geo_point"));
        properties.put("dropoff_location", Map.of("type", "geo_point"));
        properties.put("passenger_count", Map.of("type", "integer"));
        properties.put("trip_distance", Map.of("type", "double"));
        properties.put("fare_amount", Map.of("type", "double"));
        properties.put("payment_type", Map.of("type", "keyword"));
        properties.put("vendor", Map.of("type", "keyword"));
        Map<String, Object> mappings = Map.of("properties", properties);
        Map<String, Object> settings = Map.of("number_of_shards", shards);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("settings", settings);
        root.put("mappings", mappings);
        return root;
    }
}
