package com.naqqa.elasticsearch.bench;

import com.naqqa.elasticsearch.bench.dataset.TaxiLikeDataset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaxiTrack implements Track {

    public static final String TARGET_PAYMENT_TYPE = "card";
    public static final double FARE_THRESHOLD = 20.0;
    public static final double BOX_MIN_LAT = TaxiLikeDataset.MIN_LAT + (TaxiLikeDataset.MAX_LAT - TaxiLikeDataset.MIN_LAT) * 0.25;
    public static final double BOX_MAX_LAT = TaxiLikeDataset.MIN_LAT + (TaxiLikeDataset.MAX_LAT - TaxiLikeDataset.MIN_LAT) * 0.75;
    public static final double BOX_MIN_LON = TaxiLikeDataset.MIN_LON + (TaxiLikeDataset.MAX_LON - TaxiLikeDataset.MIN_LON) * 0.25;
    public static final double BOX_MAX_LON = TaxiLikeDataset.MIN_LON + (TaxiLikeDataset.MAX_LON - TaxiLikeDataset.MIN_LON) * 0.75;

    private final TaxiLikeDataset dataset;
    private final String indexName;

    public TaxiTrack(long seed, String indexName) {
        this.dataset = new TaxiLikeDataset(seed);
        this.indexName = indexName;
    }

    public TaxiLikeDataset dataset() {
        return dataset;
    }

    @Override
    public String name() {
        return "taxi";
    }

    @Override
    public String indexName() {
        return indexName;
    }

    @Override
    public Map<String, Object> mapping(int shards) {
        return TaxiLikeDataset.mapping(shards);
    }

    @Override
    public BulkIndexer.DocSource docSource() {
        return index -> dataset.toSource(dataset.trip(index));
    }

    @Override
    public Map<String, Long> groundTruth(long docCount) {
        long paymentCount = 0;
        long fareCount = 0;
        long boxCount = 0;
        for (long i = 0; i < docCount; i++) {
            TaxiLikeDataset.TaxiTrip trip = dataset.trip(i);
            if (TARGET_PAYMENT_TYPE.equals(trip.paymentType())) {
                paymentCount++;
            }
            if (trip.fareAmount() >= FARE_THRESHOLD) {
                fareCount++;
            }
            if (trip.pickupLat() >= BOX_MIN_LAT && trip.pickupLat() <= BOX_MAX_LAT
                && trip.pickupLon() >= BOX_MIN_LON && trip.pickupLon() <= BOX_MAX_LON) {
                boxCount++;
            }
        }
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("doc_count", docCount);
        stats.put("payment_count", paymentCount);
        stats.put("fare_count", fareCount);
        stats.put("box_count", boxCount);
        return stats;
    }

    @Override
    public List<QueryOp> queryOps(String index, long docCount, Map<String, Long> groundTruth) {
        List<QueryOp> ops = new ArrayList<>();
        String path = "/" + index + "/_search";
        ops.add(new QueryOp("term_payment_type", "POST", path,
            "{\"size\":0,\"query\":{\"term\":{\"payment_type\":\"" + TARGET_PAYMENT_TYPE + "\"}}}",
            groundTruth.get("payment_count")));
        ops.add(new QueryOp("range_numeric_fare", "POST", path,
            "{\"size\":0,\"query\":{\"range\":{\"fare_amount\":{\"gte\":" + FARE_THRESHOLD + "}}}}",
            groundTruth.get("fare_count")));
        ops.add(new QueryOp("range_date_pickup", "POST", path,
            "{\"size\":0,\"query\":{\"range\":{\"pickup_datetime\":{\"gte\":\"2023-06-01\"}}}}"));
        ops.add(new QueryOp("geo_bounding_box_pickup", "POST", path,
            "{\"size\":0,\"query\":{\"geo_bounding_box\":{\"pickup_location\":{\"top_left\":{\"lat\":" + BOX_MAX_LAT
                + ",\"lon\":" + BOX_MIN_LON + "},\"bottom_right\":{\"lat\":" + BOX_MIN_LAT + ",\"lon\":" + BOX_MAX_LON + "}}}}}",
            groundTruth.get("box_count")));
        double centerLat = (TaxiLikeDataset.MIN_LAT + TaxiLikeDataset.MAX_LAT) / 2;
        double centerLon = (TaxiLikeDataset.MIN_LON + TaxiLikeDataset.MAX_LON) / 2;
        ops.add(new QueryOp("geo_distance_pickup", "POST", path,
            "{\"size\":0,\"query\":{\"geo_distance\":{\"distance\":\"5km\",\"pickup_location\":{\"lat\":" + centerLat
                + ",\"lon\":" + centerLon + "}}}}"));
        ops.add(new QueryOp("terms_agg_payment_type", "POST", path,
            "{\"size\":0,\"track_total_hits\":true,\"aggs\":{\"payments\":{\"terms\":{\"field\":\"payment_type\"}}}}", docCount));
        ops.add(new QueryOp("date_histogram_agg", "POST", path,
            "{\"size\":0,\"track_total_hits\":true,"
                + "\"aggs\":{\"by_day\":{\"date_histogram\":{\"field\":\"pickup_datetime\",\"calendar_interval\":\"day\"}}}}",
            docCount));
        ops.add(new QueryOp("avg_percentiles_agg", "POST", path,
            "{\"size\":0,\"track_total_hits\":true,\"aggs\":{\"avg_fare\":{\"avg\":{\"field\":\"fare_amount\"}},"
                + "\"pct_fare\":{\"percentiles\":{\"field\":\"fare_amount\"}}}}", docCount));
        ops.add(new QueryOp("sort_by_field", "POST", path,
            "{\"size\":20,\"track_total_hits\":true,\"sort\":[{\"fare_amount\":\"desc\"}]}", docCount));
        ops.add(new QueryOp("scroll_all_docs", "POST", path + "?scroll=1m",
            "{\"size\":500,\"query\":{\"match_all\":{}}}", docCount, QueryOp.Type.SCROLL));
        return ops;
    }
}
