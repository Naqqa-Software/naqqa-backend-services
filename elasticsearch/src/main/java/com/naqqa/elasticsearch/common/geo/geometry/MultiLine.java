package com.naqqa.elasticsearch.common.geo.geometry;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class MultiLine implements Geometry, Iterable<Line> {

    public static final MultiLine EMPTY = new MultiLine(List.of());

    private final List<Line> lines;

    public MultiLine(List<Line> lines) {
        this.lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
        GeometryUtils.checkSameDimensions(this.lines);
    }

    public int size() {
        return lines.size();
    }

    public Line get(int i) {
        return lines.get(i);
    }

    public List<Line> getGeometries() {
        return lines;
    }

    @Override
    public Iterator<Line> iterator() {
        return lines.iterator();
    }

    @Override
    public ShapeType type() {
        return ShapeType.MULTILINESTRING;
    }

    @Override
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    @Override
    public boolean hasZ() {
        return !lines.isEmpty() && lines.get(0).hasZ();
    }

    @Override
    public <T> T visit(GeometryVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof MultiLine m && lines.equals(m.lines));
    }

    @Override
    public int hashCode() {
        return 19 + lines.hashCode();
    }

    @Override
    public String toString() {
        return toWKT();
    }
}
