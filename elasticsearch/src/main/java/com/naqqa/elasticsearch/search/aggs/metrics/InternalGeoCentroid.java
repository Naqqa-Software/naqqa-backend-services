package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalGeoCentroid extends InternalAggregation {

    private final double latSum;
    private final double lonSum;
    private final long count;

    public InternalGeoCentroid(String name, double latSum, double lonSum, long count, Map<String, Object> metadata) {
        super(name, metadata);
        this.latSum = latSum;
        this.lonSum = lonSum;
        this.count = count;
    }

    public GeoPoint centroid() {
        return count == 0 ? null : new GeoPoint(latSum / count, lonSum / count);
    }

    public long count() {
        return count;
    }

    public double[] sums() {
        return new double[] {latSum, lonSum};
    }

    @Override
    public String getType() {
        return "geo_centroid";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        double lat = 0;
        double lon = 0;
        long c = 0;
        for (InternalAggregation a : aggregations) {
            InternalGeoCentroid g = (InternalGeoCentroid) a;
            lat += g.latSum;
            lon += g.lonSum;
            c += g.count;
        }
        return new InternalGeoCentroid(getName(), lat, lon, c, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        GeoPoint c = centroid();
        if (c == null) {
            map.put("location", null);
        } else {
            LinkedHashMap<String, Object> loc = new LinkedHashMap<>();
            loc.put("lat", c.lat());
            loc.put("lon", c.lon());
            map.put("location", loc);
        }
        map.put("count", count);
        return map;
    }
}
