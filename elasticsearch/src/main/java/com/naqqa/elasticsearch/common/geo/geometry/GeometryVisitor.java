package com.naqqa.elasticsearch.common.geo.geometry;

public interface GeometryVisitor<T> {

    T visit(Point point);

    T visit(MultiPoint multiPoint);

    T visit(Line line);

    T visit(LinearRing ring);

    T visit(MultiLine multiLine);

    T visit(Polygon polygon);

    T visit(MultiPolygon multiPolygon);

    T visit(Rectangle rectangle);

    T visit(Circle circle);

    T visit(GeometryCollection collection);
}
