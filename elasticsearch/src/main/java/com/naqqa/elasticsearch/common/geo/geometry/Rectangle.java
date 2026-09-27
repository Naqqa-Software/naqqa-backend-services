package com.naqqa.elasticsearch.common.geo.geometry;

public final class Rectangle implements Geometry {

    public static final Rectangle EMPTY = new Rectangle();

    private final double minX;
    private final double maxX;
    private final double maxY;
    private final double minY;
    private final double maxZ;
    private final double minZ;
    private final boolean empty;

    private Rectangle() {
        this.minX = Double.NaN;
        this.maxX = Double.NaN;
        this.maxY = Double.NaN;
        this.minY = Double.NaN;
        this.maxZ = Double.NaN;
        this.minZ = Double.NaN;
        this.empty = true;
    }

    public Rectangle(double minX, double maxX, double maxY, double minY) {
        this(minX, maxX, maxY, minY, Double.NaN, Double.NaN);
    }

    public Rectangle(double minX, double maxX, double maxY, double minY, double maxZ, double minZ) {
        if (Double.isNaN(minX) || Double.isNaN(maxX) || Double.isNaN(maxY) || Double.isNaN(minY)) {
            throw new IllegalArgumentException("rectangle coordinates must not be NaN");
        }
        if (maxY < minY) {
            throw new IllegalArgumentException("max y cannot be less than min y");
        }
        if (Double.isNaN(maxZ) != Double.isNaN(minZ)) {
            throw new IllegalArgumentException("only one z value is specified");
        }
        if (!Double.isNaN(maxZ) && maxZ < minZ) {
            throw new IllegalArgumentException("max z cannot be less than min z");
        }
        this.minX = minX;
        this.maxX = maxX;
        this.maxY = maxY;
        this.minY = minY;
        this.maxZ = maxZ;
        this.minZ = minZ;
        this.empty = false;
    }

    public double getMinX() {
        return minX;
    }

    public double getMaxX() {
        return maxX;
    }

    public double getMinY() {
        return minY;
    }

    public double getMaxY() {
        return maxY;
    }

    public double getMinZ() {
        return minZ;
    }

    public double getMaxZ() {
        return maxZ;
    }

    public double getMinLon() {
        return minX;
    }

    public double getMaxLon() {
        return maxX;
    }

    public double getMinLat() {
        return minY;
    }

    public double getMaxLat() {
        return maxY;
    }

    public double getMinAlt() {
        return minZ;
    }

    public double getMaxAlt() {
        return maxZ;
    }

    public double getTop() {
        return maxY;
    }

    public double getBottom() {
        return minY;
    }

    public double getLeft() {
        return minX;
    }

    public double getRight() {
        return maxX;
    }

    public boolean crossesDateline() {
        return !empty && minX > maxX;
    }

    @Override
    public ShapeType type() {
        return ShapeType.ENVELOPE;
    }

    @Override
    public boolean isEmpty() {
        return empty;
    }

    @Override
    public boolean hasZ() {
        return !Double.isNaN(maxZ);
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
        if (!(o instanceof Rectangle r)) {
            return false;
        }
        if (empty || r.empty) {
            return empty == r.empty;
        }
        return Double.compare(minX, r.minX) == 0 && Double.compare(maxX, r.maxX) == 0 && Double.compare(minY, r.minY) == 0
            && Double.compare(maxY, r.maxY) == 0 && Double.compare(minZ, r.minZ) == 0 && Double.compare(maxZ, r.maxZ) == 0;
    }

    @Override
    public int hashCode() {
        if (empty) {
            return 0;
        }
        int h = Double.hashCode(minX);
        h = 31 * h + Double.hashCode(maxX);
        h = 31 * h + Double.hashCode(minY);
        h = 31 * h + Double.hashCode(maxY);
        h = 31 * h + Double.hashCode(minZ);
        return 31 * h + Double.hashCode(maxZ);
    }

    @Override
    public String toString() {
        return toWKT();
    }
}
