package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.geo.GeoPoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GeoBoundingBoxQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "geo_bounding_box";

    private final String fieldName;
    private final GeoPoint topLeft;
    private final GeoPoint bottomRight;
    private String validationMethod;
    private String type;

    public GeoBoundingBoxQueryBuilder(String fieldName, GeoPoint topLeft, GeoPoint bottomRight) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.topLeft = Objects.requireNonNull(topLeft);
        this.bottomRight = Objects.requireNonNull(bottomRight);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("top_left", "bottom_right", "top_right", "bottom_left",
        "top", "left", "bottom", "right", "validation_method", "type", "boost", "_name");

    public static GeoBoundingBoxQueryBuilder fromMap(Map<String, Object> value) {
        Object validationMethod = value.remove("validation_method");
        Object type = value.remove("type");
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), NAME);
        GeoPoint topLeft;
        GeoPoint bottomRight;
        if (params.containsKey("top_left") && params.containsKey("bottom_right")) {
            topLeft = GeoPoint.parse(params.remove("top_left"));
            bottomRight = GeoPoint.parse(params.remove("bottom_right"));
        } else if (params.containsKey("top_right") && params.containsKey("bottom_left")) {
            GeoPoint topRight = GeoPoint.parse(params.remove("top_right"));
            GeoPoint bottomLeft = GeoPoint.parse(params.remove("bottom_left"));
            topLeft = new GeoPoint(topRight.lat(), bottomLeft.lon());
            bottomRight = new GeoPoint(bottomLeft.lat(), topRight.lon());
        } else if (params.containsKey("top") && params.containsKey("left") && params.containsKey("bottom") && params.containsKey("right")) {
            double top = QueryParseUtils.asDouble(params.remove("top"));
            double left = QueryParseUtils.asDouble(params.remove("left"));
            double bottom = QueryParseUtils.asDouble(params.remove("bottom"));
            double right = QueryParseUtils.asDouble(params.remove("right"));
            topLeft = new GeoPoint(top, left);
            bottomRight = new GeoPoint(bottom, right);
        } else {
            throw QueryParseUtils.error("[{}] requires top_left/bottom_right, top_right/bottom_left or top/left/bottom/right", NAME);
        }
        GeoBoundingBoxQueryBuilder builder = new GeoBoundingBoxQueryBuilder(field.getKey(), topLeft, bottomRight);
        if (!params.isEmpty()) {
            throw QueryParseUtils.unknownField(NAME, params.keySet().iterator().next(), KNOWN_FIELDS);
        }
        if (validationMethod != null) {
            builder.validationMethod = QueryParseUtils.asString(validationMethod);
        }
        if (type != null) {
            builder.type = QueryParseUtils.asString(type);
        }
        for (Map.Entry<String, Object> e : value.entrySet()) {
            switch (e.getKey()) {
                case "boost", "_name" -> builder.readCommon(e.getKey(), e.getValue());
                default -> throw QueryParseUtils.unknownField(NAME, e.getKey(), KNOWN_FIELDS);
            }
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

    public GeoPoint topLeft() {
        return topLeft;
    }

    public GeoPoint bottomRight() {
        return bottomRight;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("top_left", List.of(topLeft.lon(), topLeft.lat()));
        params.put("bottom_right", List.of(bottomRight.lon(), bottomRight.lat()));
        if (validationMethod != null) {
            inner.put("validation_method", validationMethod);
        }
        if (type != null) {
            inner.put("type", type);
        }
        inner.put(fieldName, params);
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GeoBoundingBoxQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && topLeft.lat() == other.topLeft.lat()
            && topLeft.lon() == other.topLeft.lon() && bottomRight.lat() == other.bottomRight.lat() && bottomRight.lon() == other.bottomRight.lon()
            && Objects.equals(validationMethod, other.validationMethod) && Objects.equals(type, other.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, topLeft.lat(), topLeft.lon(), bottomRight.lat(), bottomRight.lon(), validationMethod, type);
    }
}
