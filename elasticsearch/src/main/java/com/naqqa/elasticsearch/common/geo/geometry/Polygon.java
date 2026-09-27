package com.naqqa.elasticsearch.common.geo.geometry;

import java.util.List;
import java.util.Objects;

public final class Polygon implements Geometry {

    public static final Polygon EMPTY = new Polygon();

    private final LinearRing shell;
    private final List<LinearRing> holes;

    private Polygon() {
        this.shell = LinearRing.EMPTY;
        this.holes = List.of();
    }

    public Polygon(LinearRing shell) {
        this(shell, List.of());
    }

    public Polygon(LinearRing shell, List<LinearRing> holes) {
        this.shell = Objects.requireNonNull(shell, "shell must not be null");
        this.holes = List.copyOf(Objects.requireNonNull(holes, "holes must not be null"));
        if (shell.isEmpty() && !this.holes.isEmpty()) {
            throw new IllegalArgumentException("polygon cannot have an empty shell and non-empty holes");
        }
        boolean z = shell.hasZ();
        for (LinearRing hole : this.holes) {
            if (hole.isEmpty()) {
                throw new IllegalArgumentException("holes must not be empty");
            }
            if (hole.hasZ() != z) {
                throw new IllegalArgumentException("holes and shell must all have the same dimensions");
            }
        }
    }

    public LinearRing getPolygon() {
        return shell;
    }

    public int getNumberOfHoles() {
        return holes.size();
    }

    public LinearRing getHole(int i) {
        return holes.get(i);
    }

    public List<LinearRing> getHoles() {
        return holes;
    }

    @Override
    public ShapeType type() {
        return ShapeType.POLYGON;
    }

    @Override
    public boolean isEmpty() {
        return shell.isEmpty();
    }

    @Override
    public boolean hasZ() {
        return shell.hasZ();
    }

    @Override
    public <T> T visit(GeometryVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Polygon p && shell.equals(p.shell) && holes.equals(p.holes);
    }

    @Override
    public int hashCode() {
        return 31 * shell.hashCode() + holes.hashCode();
    }

    @Override
    public String toString() {
        return toWKT();
    }
}
