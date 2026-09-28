package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.bench.dataset.TaxiLikeDataset;
import com.naqqa.elasticsearch.test.Test;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class TaxiLikeDatasetTest {

    @Test
    public void sameSeedProducesIdenticalTrips() {
        TaxiLikeDataset a = new TaxiLikeDataset(42L);
        TaxiLikeDataset b = new TaxiLikeDataset(42L);
        for (long i = 0; i < 50; i++) {
            assertEquals(a.trip(i), b.trip(i));
        }
    }

    @Test
    public void differentSeedProducesDifferentTrips() {
        TaxiLikeDataset a = new TaxiLikeDataset(1L);
        TaxiLikeDataset b = new TaxiLikeDataset(2L);
        boolean anyDifferent = false;
        for (long i = 0; i < 20; i++) {
            if (Double.compare(a.trip(i).fareAmount(), b.trip(i).fareAmount()) != 0) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "expected different seeds to diverge");
    }

    @Test
    public void tripsStayWithinBoundingBoxAndHaveConsistentTiming() {
        TaxiLikeDataset dataset = new TaxiLikeDataset(11L);
        for (long i = 0; i < 500; i++) {
            TaxiLikeDataset.TaxiTrip trip = dataset.trip(i);
            assertTrue(trip.pickupLat() >= TaxiLikeDataset.MIN_LAT && trip.pickupLat() <= TaxiLikeDataset.MAX_LAT,
                "pickup lat out of bbox: " + trip.pickupLat());
            assertTrue(trip.pickupLon() >= TaxiLikeDataset.MIN_LON && trip.pickupLon() <= TaxiLikeDataset.MAX_LON,
                "pickup lon out of bbox: " + trip.pickupLon());
            assertTrue(trip.dropoffLat() >= TaxiLikeDataset.MIN_LAT && trip.dropoffLat() <= TaxiLikeDataset.MAX_LAT,
                "dropoff lat out of bbox: " + trip.dropoffLat());
            assertTrue(trip.dropoffLon() >= TaxiLikeDataset.MIN_LON && trip.dropoffLon() <= TaxiLikeDataset.MAX_LON,
                "dropoff lon out of bbox: " + trip.dropoffLon());
            assertTrue(trip.dropoffMillis() > trip.pickupMillis(), "dropoff should be after pickup");
            assertTrue(trip.tripDistance() >= 0, "trip distance should be non-negative");
            assertTrue(trip.fareAmount() > 0, "fare should be positive");
            assertTrue(trip.passengerCount() >= 1 && trip.passengerCount() <= 6, "passenger count out of range");
        }
    }

    @Test
    public void toSourceProducesExpectedFields() {
        TaxiLikeDataset dataset = new TaxiLikeDataset(4L);
        TaxiLikeDataset.TaxiTrip trip = dataset.trip(0);
        var source = dataset.toSource(trip);
        assertEquals(trip.paymentType(), source.get("payment_type"));
        assertEquals(trip.vendor(), source.get("vendor"));
        @SuppressWarnings("unchecked")
        var pickup = (java.util.Map<String, Object>) source.get("pickup_location");
        assertEquals(trip.pickupLat(), ((Number) pickup.get("lat")).doubleValue(), 1e-9);
        assertEquals(trip.pickupLon(), ((Number) pickup.get("lon")).doubleValue(), 1e-9);
    }

    @Test
    public void mappingDeclaresGeoPointFields() {
        var mapping = TaxiLikeDataset.mapping(1);
        @SuppressWarnings("unchecked")
        var mappings = (java.util.Map<String, Object>) mapping.get("mappings");
        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>) mappings.get("properties");
        assertEquals("geo_point", ((java.util.Map<?, ?>) properties.get("pickup_location")).get("type"));
        assertEquals("geo_point", ((java.util.Map<?, ?>) properties.get("dropoff_location")).get("type"));
        assertEquals("keyword", ((java.util.Map<?, ?>) properties.get("payment_type")).get("type"));
    }
}
