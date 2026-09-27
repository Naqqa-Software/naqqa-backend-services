package com.naqqa.elasticsearch.common.geo.geometry;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class MultiPoint implements Geometry, Iterable<Point> {

    public static final MultiPoint EMPTY = new MultiPoint(List.of());

    private final List<Point> points;

    public MultiPoint(List<Point> points) {
        this.points = List.copyOf(Objects.requireNonNull(points, "points must not be null"));
        GeometryUtils.checkSameDimensions(this.points);
    }

    public int size() {
        return points.size();
    }

    public Point get(int i) {
        return points.get(i);
    }

    public List<Point> getGeometries() {
        return points;
    }

    @Override
    public Iterator<Point> iterator() {
        return points.iterator();
    }

    @Override
    public ShapeType type() {
        return ShapeType.MULTIPOINT;
    }

    @Override
    public boolean isEmpty() {
        return points.isEmpty();
    }

    @Override
    public boolean hasZ() {
        return !points.isEmpty() && points.get(0).hasZ();
    }

    @Override
    public <T> T visit(GeometryVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof MultiPoint m && points.equals(m.points));
    }

    @Override
    public int hashCode() {
        return 17 + points.hashCode();
    }

    @Override
    public String toString() {
        return toWKT();
    }
}
