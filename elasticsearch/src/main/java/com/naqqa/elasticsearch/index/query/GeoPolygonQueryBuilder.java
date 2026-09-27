package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.geo.GeoPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class GeoPolygonQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "geo_polygon";

    private final String fieldName;
    private final List<GeoPoint> points;
    private String validationMethod;

    public GeoPolygonQueryBuilder(String fieldName, List<GeoPoint> points) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.points = new ArrayList<>(Objects.requireNonNull(points));
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("points", "validation_method", "boost", "_name");

    public static GeoPolygonQueryBuilder fromMap(Map<String, Object> value) {
        Object validationMethod = value.remove("validation_method");
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), NAME);
        Object pointsObj = params.remove("points");
        if (pointsObj == null) {
            throw QueryParseUtils.error("[{}] requires [points]", NAME);
        }
        List<GeoPoint> points = new ArrayList<>();
        for (Object o : QueryParseUtils.asList(pointsObj, NAME)) {
            points.add(GeoPoint.parse(o));
        }
        GeoPolygonQueryBuilder builder = new GeoPolygonQueryBuilder(field.getKey(), points);
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

    public List<GeoPoint> points() {
        return points;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("points", points.stream().map(p -> List.of(p.lon(), p.lat())).collect(Collectors.toList()));
        if (validationMethod != null) {
            inner.put("validation_method", validationMethod);
        }
        inner.put(fieldName, params);
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GeoPolygonQueryBuilder other)) {
            return false;
        }
        if (!commonEquals(other) || !fieldName.equals(other.fieldName) || points.size() != other.points.size() || !Objects.equals(validationMethod, other.validationMethod)) {
            return false;
        }
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).lat() != other.points.get(i).lat() || points.get(i).lon() != other.points.get(i).lon()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, points.size(), validationMethod);
    }
}
