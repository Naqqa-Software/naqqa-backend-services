package com.naqqa.elasticsearch.common.geo.geometry;

import com.naqqa.elasticsearch.common.geo.format.WellKnownText;

public sealed interface Geometry permits Point, MultiPoint, Line, MultiLine, Polygon, MultiPolygon, Rectangle, Circle, GeometryCollection {

    ShapeType type();

    boolean isEmpty();

    boolean hasZ();

    <T> T visit(GeometryVisitor<T> visitor);

    default String toWKT() {
        return WellKnownText.toWKT(this);
    }
}
