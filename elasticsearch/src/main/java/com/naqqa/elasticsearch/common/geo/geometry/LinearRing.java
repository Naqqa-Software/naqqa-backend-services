package com.naqqa.elasticsearch.common.geo.geometry;

public final class LinearRing extends Line {

    public static final LinearRing EMPTY = new LinearRing();

    private LinearRing() {
        super();
    }

    public LinearRing(double[] x, double[] y) {
        this(x, y, null);
    }

    public LinearRing(double[] x, double[] y, double[] z) {
        super(x, y, z);
        int last = x.length - 1;
        if (x[0] != x[last] || y[0] != y[last] || (z != null && Double.compare(z[0], z[last]) != 0)) {
            throw new IllegalArgumentException("first and last points of the linear ring must be the same (it must close itself): x[0]="
                + x[0] + " x[" + last + "]=" + x[last] + " y[0]=" + y[0] + " y[" + last + "]=" + y[last]);
        }
        if (x.length < 4) {
            throw new IllegalArgumentException("at least 4 points are required for a linear ring, found " + x.length);
        }
    }

    @Override
    public ShapeType type() {
        return ShapeType.LINEARRING;
    }

    @Override
    public <T> T visit(GeometryVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
