package com.naqqa.elasticsearch.common.geo.geometry;

import com.naqqa.elasticsearch.common.geo.GeoUtils;
import com.naqqa.elasticsearch.common.geo.shape.Polygon2D;

import java.util.ArrayList;
import java.util.List;

public final class GeometryUtils {

    private GeometryUtils() {
    }

    static void checkSameDimensions(List<? extends Geometry> geometries) {
        Boolean z = null;
        for (Geometry g : geometries) {
            if (g == null) {
                throw new IllegalArgumentException("geometries must not contain null");
            }
            if (g.isEmpty()) {
                continue;
            }
            if (z == null) {
                z = g.hasZ();
            } else if (z != g.hasZ()) {
                throw new IllegalArgumentException("all elements of the collection should have the same number of dimension");
            }
        }
    }

    public static double signedArea(Line ring) {
        int n = ring.length();
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += ring.getX(i) * ring.getY(j) - ring.getX(j) * ring.getY(i);
        }
        return sum / 2.0;
    }

    public static double signedArea(double[] xs, double[] ys) {
        int n = xs.length;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return sum / 2.0;
    }

    public static double planarArea(Polygon polygon) {
        if (polygon.isEmpty()) {
            return 0;
        }
        double area = Math.abs(signedArea(polygon.getPolygon()));
        for (LinearRing hole : polygon.getHoles()) {
            area -= Math.abs(signedArea(hole));
        }
        return area;
    }

    public static double planarArea(Geometry geometry) {
        return switch (geometry) {
            case Polygon p -> planarArea(p);
            case MultiPolygon mp -> {
                double a = 0;
                for (Polygon p : mp) {
                    a += planarArea(p);
                }
                yield a;
            }
            case Rectangle r -> r.isEmpty() ? 0 : (width(r.getMinX(), r.getMaxX())) * (r.getMaxY() - r.getMinY());
            case Circle c -> c.isEmpty() ? 0 : Math.PI * c.getRadiusMeters() * c.getRadiusMeters();
            case GeometryCollection gc -> {
                double a = 0;
                for (Geometry g : gc) {
                    a += planarArea(g);
                }
                yield a;
            }
            default -> 0;
        };
    }

    private static double width(double minX, double maxX) {
        return minX <= maxX ? maxX - minX : (180 - minX) + (maxX + 180);
    }

    public static boolean pointInPolygon(Polygon polygon, double x, double y) {
        if (polygon.isEmpty()) {
            return false;
        }
        return Polygon2D.create(polygon).contains(x, y);
    }

    public static boolean rectangleContains(Rectangle rectangle, double x, double y) {
        if (rectangle.isEmpty() || y < rectangle.getMinY() || y > rectangle.getMaxY()) {
            return false;
        }
        if (rectangle.crossesDateline()) {
            return x >= rectangle.getMinX() || x <= rectangle.getMaxX();
        }
        return x >= rectangle.getMinX() && x <= rectangle.getMaxX();
    }

    public static List<Rectangle> splitAtDateline(Rectangle rectangle) {
        if (!rectangle.crossesDateline()) {
            return List.of(rectangle);
        }
        return List.of(
            new Rectangle(rectangle.getMinX(), 180, rectangle.getMaxY(), rectangle.getMinY()),
            new Rectangle(-180, rectangle.getMaxX(), rectangle.getMaxY(), rectangle.getMinY())
        );
    }

    public static Rectangle boundingBox(Geometry geometry) {
        return boundingBox(geometry, true, false);
    }

    public static Rectangle cartesianBoundingBox(Geometry geometry) {
        return boundingBox(geometry, false, false);
    }

    public static Rectangle boundingBox(Geometry geometry, boolean geo, boolean wrapLongitude) {
        BoundsAccumulator acc = new BoundsAccumulator(geo);
        acc.add(geometry);
        return acc.result(wrapLongitude && geo);
    }

    public static final class BoundsAccumulator {
        private final boolean geo;
        private double top = Double.NEGATIVE_INFINITY;
        private double bottom = Double.POSITIVE_INFINITY;
        private double posLeft = Double.POSITIVE_INFINITY;
        private double posRight = Double.NEGATIVE_INFINITY;
        private double negLeft = Double.POSITIVE_INFINITY;
        private double negRight = Double.NEGATIVE_INFINITY;

        public BoundsAccumulator(boolean geo) {
            this.geo = geo;
        }

        public void addPoint(double x, double y) {
            top = Math.max(top, y);
            bottom = Math.min(bottom, y);
            if (x >= 0) {
                posLeft = Math.min(posLeft, x);
                posRight = Math.max(posRight, x);
            } else {
                negLeft = Math.min(negLeft, x);
                negRight = Math.max(negRight, x);
            }
        }

        public void add(Geometry geometry) {
            if (geometry == null || geometry.isEmpty()) {
                return;
            }
            switch (geometry) {
                case Point p -> addPoint(p.getX(), p.getY());
                case MultiPoint mp -> {
                    for (Point p : mp) {
                        add(p);
                    }
                }
                case Line l -> {
                    for (int i = 0; i < l.length(); i++) {
                        addPoint(l.getX(i), l.getY(i));
                    }
                }
                case MultiLine ml -> {
                    for (Line l : ml) {
                        add(l);
                    }
                }
                case Polygon p -> add(p.getPolygon());
                case MultiPolygon mp -> {
                    for (Polygon p : mp) {
                        add(p);
                    }
                }
                case Rectangle r -> {
                    if (r.crossesDateline()) {
                        addPoint(r.getMinX(), r.getMinY());
                        addPoint(180, r.getMaxY());
                        addPoint(-180, r.getMinY());
                        addPoint(r.getMaxX(), r.getMaxY());
                    } else {
                        addPoint(r.getMinX(), r.getMinY());
                        addPoint(r.getMaxX(), r.getMaxY());
                    }
                }
                case Circle c -> {
                    if (geo) {
                        add(GeoUtils.circleToBBox(c.getY(), c.getX(), c.getRadiusMeters()));
                    } else {
                        addPoint(c.getX() - c.getRadiusMeters(), c.getY() - c.getRadiusMeters());
                        addPoint(c.getX() + c.getRadiusMeters(), c.getY() + c.getRadiusMeters());
                    }
                }
                case GeometryCollection gc -> {
                    for (Geometry g : gc) {
                        add(g);
                    }
                }
            }
        }

        public Rectangle result(boolean wrapLongitude) {
            if (Double.isInfinite(top)) {
                return null;
            }
            if (Double.isInfinite(posLeft)) {
                return new Rectangle(negLeft, negRight, top, bottom);
            }
            if (Double.isInfinite(negLeft)) {
                return new Rectangle(posLeft, posRight, top, bottom);
            }
            if (wrapLongitude) {
                double unwrappedWidth = posRight - negLeft;
                double wrappedWidth = (180 - posLeft) - (-180 - negRight);
                if (unwrappedWidth <= wrappedWidth) {
                    return new Rectangle(negLeft, posRight, top, bottom);
                }
                return new Rectangle(posLeft, negRight, top, bottom);
            }
            return new Rectangle(negLeft, posRight, top, bottom);
        }
    }

    public static List<Geometry> flatten(Geometry geometry) {
        List<Geometry> out = new ArrayList<>();
        flatten(geometry, out);
        return out;
    }

    private static void flatten(Geometry geometry, List<Geometry> out) {
        if (geometry == null || geometry.isEmpty()) {
            return;
        }
        switch (geometry) {
            case MultiPoint mp -> out.addAll(mp.getGeometries());
            case MultiLine ml -> out.addAll(ml.getGeometries());
            case MultiPolygon mp -> out.addAll(mp.getGeometries());
            case GeometryCollection gc -> {
                for (Geometry g : gc) {
                    flatten(g, out);
                }
            }
            default -> out.add(geometry);
        }
    }
}
