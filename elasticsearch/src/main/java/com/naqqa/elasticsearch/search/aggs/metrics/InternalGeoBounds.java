package com.naqqa.elasticsearch.search.aggs.metrics;

import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InternalGeoBounds extends InternalAggregation {

    private final double top;
    private final double bottom;
    private final double left;
    private final double right;

    public InternalGeoBounds(String name, double top, double bottom, double left, double right, Map<String, Object> metadata) {
        super(name, metadata);
        this.top = top;
        this.bottom = bottom;
        this.left = left;
        this.right = right;
    }

    @Override
    public String getType() {
        return "geo_bounds";
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext context) {
        double t = Double.NEGATIVE_INFINITY;
        double b = Double.POSITIVE_INFINITY;
        double l = Double.POSITIVE_INFINITY;
        double r = Double.NEGATIVE_INFINITY;
        for (InternalAggregation a : aggregations) {
            InternalGeoBounds g = (InternalGeoBounds) a;
            t = Math.max(t, g.top);
            b = Math.min(b, g.bottom);
            l = Math.min(l, g.left);
            r = Math.max(r, g.right);
        }
        return new InternalGeoBounds(getName(), t, b, l, r, getMetadata());
    }

    @Override
    public Map<String, Object> toMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        if (Double.isInfinite(top)) {
            map.put("bounds", null);
            return map;
        }
        LinkedHashMap<String, Object> topLeft = new LinkedHashMap<>();
        topLeft.put("lat", top);
        topLeft.put("lon", left);
        LinkedHashMap<String, Object> bottomRight = new LinkedHashMap<>();
        bottomRight.put("lat", bottom);
        bottomRight.put("lon", right);
        LinkedHashMap<String, Object> bounds = new LinkedHashMap<>();
        bounds.put("top_left", topLeft);
        bounds.put("bottom_right", bottomRight);
        map.put("bounds", bounds);
        return map;
    }
}
