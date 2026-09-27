package com.naqqa.elasticsearch.common.geo.geometry;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class GeometryCollection implements Geometry, Iterable<Geometry> {

    public static final GeometryCollection EMPTY = new GeometryCollection(List.of());

    private final List<Geometry> geometries;

    public GeometryCollection(List<? extends Geometry> geometries) {
        this.geometries = List.copyOf(Objects.requireNonNull(geometries, "geometries must not be null"));
        GeometryUtils.checkSameDimensions(this.geometries);
    }

    public int size() {
        return geometries.size();
    }

    public Geometry get(int i) {
        return geometries.get(i);
    }

    public List<Geometry> getGeometries() {
        return geometries;
    }

    @Override
    public Iterator<Geometry> iterator() {
        return geometries.iterator();
    }

    @Override
    public ShapeType type() {
        return ShapeType.GEOMETRYCOLLECTION;
    }

    @Override
    public boolean isEmpty() {
        return geometries.isEmpty();
    }

    @Override
    public boolean hasZ() {
        return !geometries.isEmpty() && geometries.get(0).hasZ();
    }

    @Override
    public <T> T visit(GeometryVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof GeometryCollection g && geometries.equals(g.geometries));
    }

    @Override
    public int hashCode() {
        return 29 + geometries.hashCode();
    }

    @Override
    public String toString() {
        return toWKT();
    }
}
