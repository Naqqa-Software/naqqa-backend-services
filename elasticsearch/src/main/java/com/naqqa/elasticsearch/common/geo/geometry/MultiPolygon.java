package com.naqqa.elasticsearch.common.geo.geometry;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class MultiPolygon implements Geometry, Iterable<Polygon> {

    public static final MultiPolygon EMPTY = new MultiPolygon(List.of());

    private final List<Polygon> polygons;

    public MultiPolygon(List<Polygon> polygons) {
        this.polygons = List.copyOf(Objects.requireNonNull(polygons, "polygons must not be null"));
        GeometryUtils.checkSameDimensions(this.polygons);
    }

    public int size() {
        return polygons.size();
    }

    public Polygon get(int i) {
        return polygons.get(i);
    }

    public List<Polygon> getGeometries() {
        return polygons;
    }

    @Override
    public Iterator<Polygon> iterator() {
        return polygons.iterator();
    }

    @Override
    public ShapeType type() {
        return ShapeType.MULTIPOLYGON;
    }

    @Override
    public boolean isEmpty() {
        return polygons.isEmpty();
    }

    @Override
    public boolean hasZ() {
        return !polygons.isEmpty() && polygons.get(0).hasZ();
    }

    @Override
    public <T> T visit(GeometryVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof MultiPolygon m && polygons.equals(m.polygons));
    }

    @Override
    public int hashCode() {
        return 23 + polygons.hashCode();
    }

    @Override
    public String toString() {
        return toWKT();
    }
}
