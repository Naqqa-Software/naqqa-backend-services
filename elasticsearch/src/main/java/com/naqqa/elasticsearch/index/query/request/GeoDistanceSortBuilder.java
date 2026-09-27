package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.common.geo.DistanceUnit;
import com.naqqa.elasticsearch.common.geo.GeoDistance;
import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class GeoDistanceSortBuilder implements SortBuilder {

    private final String fieldName;
    private final List<GeoPoint> points = new ArrayList<>();
    private SortOrder order = SortOrder.ASC;
    private DistanceUnit unit = DistanceUnit.METERS;
    private GeoDistance distanceType = GeoDistance.ARC;
    private SortMode mode;
    private NestedSortBuilder nested;

    public GeoDistanceSortBuilder(String fieldName) {
        this.fieldName = Objects.requireNonNull(fieldName);
    }

    public static GeoDistanceSortBuilder fromMap(String fieldName, Object pointsValue, Map<String, Object> params) {
        GeoDistanceSortBuilder builder = new GeoDistanceSortBuilder(fieldName);
        if (pointsValue instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number) {
            builder.points.add(GeoPoint.parse(pointsValue));
        } else {
            for (Object o : QueryParseUtils.asList(pointsValue, "geo_distance sort")) {
                builder.points.add(GeoPoint.parse(o));
            }
        }
        Object order = params.remove("order");
        if (order != null) {
            builder.order = SortOrder.fromString(QueryParseUtils.asString(order));
        }
        Object unit = params.remove("unit");
        if (unit != null) {
            builder.unit = DistanceUnit.fromString(QueryParseUtils.asString(unit));
        }
        Object distanceType = params.remove("distance_type");
        if (distanceType != null) {
            builder.distanceType = GeoDistance.fromString(QueryParseUtils.asString(distanceType));
        }
        Object mode = params.remove("mode");
        if (mode != null) {
            builder.mode = SortMode.fromString(QueryParseUtils.asString(mode));
        }
        Object nested = params.remove("nested");
        if (nested != null) {
            builder.nested = NestedSortBuilder.fromMap(QueryParseUtils.asMap(nested, "nested"));
        }
        return builder;
    }

    public String fieldName() {
        return fieldName;
    }

    public List<GeoPoint> points() {
        return points;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(fieldName, points.stream().map(p -> List.of(p.lon(), p.lat())).collect(Collectors.toList()));
        if (order != SortOrder.ASC) {
            m.put("order", order.toValue());
        }
        if (unit != DistanceUnit.METERS) {
            m.put("unit", unit.getName());
        }
        if (distanceType != GeoDistance.ARC) {
            m.put("distance_type", "plane");
        }
        if (mode != null) {
            m.put("mode", mode.toValue());
        }
        if (nested != null) {
            m.put("nested", nested.toMap());
        }
        return Map.of("_geo_distance", m);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GeoDistanceSortBuilder other)) {
            return false;
        }
        return fieldName.equals(other.fieldName) && points.size() == other.points.size() && order == other.order
            && unit == other.unit && distanceType == other.distanceType && mode == other.mode && Objects.equals(nested, other.nested);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, points.size(), order, unit, distanceType, mode, nested);
    }
}
