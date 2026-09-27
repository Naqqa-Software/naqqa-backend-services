package com.naqqa.elasticsearch.common.geo.geometry;

public final class Circle implements Geometry {

    public static final Circle EMPTY = new Circle();

    private final double x;
    private final double y;
    private final double z;
    private final double radiusMeters;
    private final boolean empty;

    private Circle() {
        this.x = Double.NaN;
        this.y = Double.NaN;
        this.z = Double.NaN;
        this.radiusMeters = -1;
        this.empty = true;
    }

    public Circle(double x, double y, double radiusMeters) {
        this(x, y, Double.NaN, radiusMeters);
    }

    public Circle(double x, double y, double z, double radiusMeters) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            throw new IllegalArgumentException("circle center must not be NaN");
        }
        if (Double.isNaN(radiusMeters) || radiusMeters < 0) {
            throw new IllegalArgumentException("circle radius [" + radiusMeters + "] cannot be negative");
        }
        this.x = x;
        this.y = y;
        this.z = z;
        this.radiusMeters = radiusMeters;
        this.empty = false;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public double getLon() {
        return x;
    }

    public double getLat() {
        return y;
    }

    public double getAlt() {
        return z;
    }

    public double getRadiusMeters() {
        return radiusMeters;
    }

    @Override
    public ShapeType type() {
        return ShapeType.CIRCLE;
    }

    @Override
    public boolean isEmpty() {
        return empty;
    }

    @Override
    public boolean hasZ() {
        return !Double.isNaN(z);
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
        if (!(o instanceof Circle c)) {
            return false;
        }
        if (empty || c.empty) {
            return empty == c.empty;
        }
        return Double.compare(x, c.x) == 0 && Double.compare(y, c.y) == 0 && Double.compare(z, c.z) == 0
            && Double.compare(radiusMeters, c.radiusMeters) == 0;
    }

    @Override
    public int hashCode() {
        if (empty) {
            return 0;
        }
        int h = Double.hashCode(x);
        h = 31 * h + Double.hashCode(y);
        h = 31 * h + Double.hashCode(z);
        return 31 * h + Double.hashCode(radiusMeters);
    }

    @Override
    public String toString() {
        return toWKT();
    }
}
