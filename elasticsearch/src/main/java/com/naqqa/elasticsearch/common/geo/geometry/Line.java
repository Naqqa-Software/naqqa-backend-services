package com.naqqa.elasticsearch.common.geo.geometry;

import java.util.Arrays;

public sealed class Line implements Geometry permits LinearRing {

    public static final Line EMPTY = new Line();

    private final double[] x;
    private final double[] y;
    private final double[] z;

    protected Line() {
        this.x = new double[0];
        this.y = new double[0];
        this.z = null;
    }

    public Line(double[] x, double[] y) {
        this(x, y, null);
    }

    public Line(double[] x, double[] y, double[] z) {
        if (x == null || y == null) {
            throw new IllegalArgumentException("x and y must not be null");
        }
        if (x.length != y.length) {
            throw new IllegalArgumentException("x and y must be equal length");
        }
        if (z != null && z.length != x.length) {
            throw new IllegalArgumentException("z must be the same length as x and y");
        }
        if (x.length < 2) {
            throw new IllegalArgumentException("at least two points in the line is required");
        }
        this.x = x.clone();
        this.y = y.clone();
        this.z = z == null ? null : z.clone();
    }

    public int length() {
        return x.length;
    }

    public double getX(int i) {
        return x[i];
    }

    public double getY(int i) {
        return y[i];
    }

    public double getZ(int i) {
        return z == null ? Double.NaN : z[i];
    }

    public double getLon(int i) {
        return x[i];
    }

    public double getLat(int i) {
        return y[i];
    }

    public double[] getX() {
        return x.clone();
    }

    public double[] getY() {
        return y.clone();
    }

    public double[] getZ() {
        return z == null ? null : z.clone();
    }

    public double[] getLons() {
        return getX();
    }

    public double[] getLats() {
        return getY();
    }

    @Override
    public ShapeType type() {
        return ShapeType.LINESTRING;
    }

    @Override
    public boolean isEmpty() {
        return x.length == 0;
    }

    @Override
    public boolean hasZ() {
        return z != null;
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
        if (o == null || o.getClass() != getClass()) {
            return false;
        }
        Line l = (Line) o;
        return Arrays.equals(x, l.x) && Arrays.equals(y, l.y) && Arrays.equals(z, l.z);
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(x);
        h = 31 * h + Arrays.hashCode(y);
        return 31 * h + Arrays.hashCode(z);
    }

    @Override
    public String toString() {
        return toWKT();
    }
}
