package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.common.geo.format.GeoJson;
import com.naqqa.elasticsearch.common.geo.geometry.Geometry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GeoShapeQueryBuilder extends AbstractQueryBuilder {

    public static final String NAME = "geo_shape";

    public enum ShapeRelation {
        INTERSECTS, DISJOINT, WITHIN, CONTAINS;

        static ShapeRelation fromString(String s) {
            return ShapeRelation.valueOf(s.toUpperCase(Locale.ROOT));
        }

        String toValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String fieldName;
    private Geometry shape;
    private TermsLookup indexedShape;
    private ShapeRelation relation = ShapeRelation.INTERSECTS;
    private boolean ignoreUnmapped = false;

    public GeoShapeQueryBuilder(String fieldName, Geometry shape) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.shape = Objects.requireNonNull(shape);
    }

    public GeoShapeQueryBuilder(String fieldName, TermsLookup indexedShape) {
        this.fieldName = Objects.requireNonNull(fieldName);
        this.indexedShape = Objects.requireNonNull(indexedShape);
    }

    private static final Set<String> KNOWN_FIELDS = Set.of("shape", "indexed_shape", "relation", "ignore_unmapped", "boost", "_name");

    public static GeoShapeQueryBuilder fromMap(Map<String, Object> value) {
        Object ignoreUnmapped = value.remove("ignore_unmapped");
        Map.Entry<String, Object> field = QueryParseUtils.singleField(NAME, value);
        Map<String, Object> params = QueryParseUtils.asMap(field.getValue(), NAME);
        Object shape = params.remove("shape");
        Object indexedShape = params.remove("indexed_shape");
        Object relation = params.remove("relation");
        GeoShapeQueryBuilder builder;
        if (shape != null) {
            builder = new GeoShapeQueryBuilder(field.getKey(), GeoJson.fromMap(QueryParseUtils.asMap(shape, NAME)));
        } else if (indexedShape != null) {
            builder = new GeoShapeQueryBuilder(field.getKey(), TermsLookup.fromMap(QueryParseUtils.asMap(indexedShape, NAME)));
        } else {
            throw QueryParseUtils.error("[{}] requires either [shape] or [indexed_shape]", NAME);
        }
        if (relation != null) {
            builder.relation = ShapeRelation.fromString(QueryParseUtils.asString(relation));
        }
        if (ignoreUnmapped != null) {
            builder.ignoreUnmapped = QueryParseUtils.asBoolean(ignoreUnmapped);
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

    public Geometry shape() {
        return shape;
    }

    @Override
    protected void doToInnerMap(Map<String, Object> inner) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (shape != null) {
            params.put("shape", GeoJson.toMap(shape));
        } else {
            params.put("indexed_shape", indexedShape.toMap());
        }
        if (relation != ShapeRelation.INTERSECTS) {
            params.put("relation", relation.toValue());
        }
        inner.put(fieldName, params);
        if (ignoreUnmapped) {
            inner.put("ignore_unmapped", true);
        }
        writeCommon(inner);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GeoShapeQueryBuilder other)) {
            return false;
        }
        return commonEquals(other) && fieldName.equals(other.fieldName) && Objects.equals(shape, other.shape)
            && Objects.equals(indexedShape, other.indexedShape) && relation == other.relation && ignoreUnmapped == other.ignoreUnmapped;
    }

    @Override
    public int hashCode() {
        return Objects.hash(commonHash(), fieldName, shape, indexedShape, relation, ignoreUnmapped);
    }
}
