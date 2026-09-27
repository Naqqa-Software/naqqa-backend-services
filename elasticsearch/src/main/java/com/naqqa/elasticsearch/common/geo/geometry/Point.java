package com.naqqa.elasticsearch.common.geo.geometry;

public final class Point implements Geometry {

    public static final Point EMPTY = new Point();

    private final double x;
    private final double y;
    private final double z;
    private final boolean empty;

    private Point() {
        this.x = Double.NaN;
        this.y = Double.NaN;
        this.z = Double.NaN;
        this.empty = true;
    }

    public Point(double x, double y) {
        this(x, y, Double.NaN);
    }

    public Point(double x, double y, double z) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            throw new IllegalArgumentException("point coordinates must not be NaN");
        }
        this.x = x;
        this.y = y;
        this.z = z;
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

    @Override
    public ShapeType type() {
        return ShapeType.POINT;
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
        if (!(o instanceof Point p)) {
            return false;
        }
        if (empty || p.empty) {
            return empty == p.empty;
        }
        return Double.compare(x, p.x) == 0 && Double.compare(y, p.y) == 0 && Double.compare(z, p.z) == 0;
    }

    @Override
    public int hashCode() {
        if (empty) {
            return 0;
        }
        int h = Double.hashCode(x);
        h = 31 * h + Double.hashCode(y);
        return 31 * h + Double.hashCode(z);
    }

    @Override
    public String toString() {
        return toWKT();
    }
}
