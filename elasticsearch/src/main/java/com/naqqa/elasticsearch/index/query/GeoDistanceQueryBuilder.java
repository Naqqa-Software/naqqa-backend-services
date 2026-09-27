package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.geo.GeoPoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GeoDistanceQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "geo_distance";

    private final String fieldName;
    private final GeoPoint point;
    private String distance;
    private String distanceType;
    private String validationMethod;

    public GeoDistanceQueryBuilder(String fieldName, GeoPoint point, String distance) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.point = Objects.requireNonNull(point);
        this.distance = Objects.requireNonNull(distance);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("distance", "distance_type", "validation_method", "boost", "_name");

    public static GeoDistanceQueryBuilder fromMap(Map<String, Object> value) {
        Object distance = value.remove("distance");
        Object distanceType = value.remove("distance_type");
        Object validationMethod = value.remove("validation_method");
        if (distance == null) {
            throw QueryParseUtils.error("[{}] requires a [distance]", NAME);
        }
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        GeoDistanceQueryBuilder builder = new GeoDistanceQueryBuilder(field.getKey(), GeoPoint.parse(field.getValue()), QueryParseUtils.asString(distance));
        if (distanceType != null) {
            builder.distanceType = QueryParseUtils.asString(distanceType).toLowerCase(Locale.ROOT);
        }
        if (validationMethod != null) {
            builder.validationMethod = QueryParseUtils.asString(validationMethod);
        }
        return builder;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    public String fieldName() {
        return fieldName;
    }

    public GeoPoint point() {
        return point;
    }

    public String distance() {
        return distance;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        inner.put("distance", distance);
        if (distanceType != null) {
            inner.put("distance_type", distanceType);
        }
        if (validationMethod != null) {
            inner.put("validation_method", validationMethod);
        }
        inner.put(fieldName, List.of(point.lon(), point.lat()));
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GeoDistanceQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && point.lat() == other.point.lat() && point.lon() == other.point.lon()
            && distance.equals(other.distance) && Objects.equals(distanceType, other.distanceType) && Objects.equals(validationMethod, other.validationMethod);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, point.lat(), point.lon(), distance, distanceType, validationMethod);
    }
}
