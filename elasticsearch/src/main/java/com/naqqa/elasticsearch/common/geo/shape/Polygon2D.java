package com.naqqa.elasticsearch.common.geo.shape;

import com.naqqa.elasticsearch.common.geo.geometry.LinearRing;
import com.naqqa.elasticsearch.common.geo.geometry.MultiPolygon;
import com.naqqa.elasticsearch.common.geo.geometry.Polygon;

import java.util.ArrayList;
import java.util.List;

public final class Polygon2D {

    private final List<Ring> shells;

    private Polygon2D(List<Ring> shells) {
        this.shells = shells;
    }

    public static Polygon2D create(Polygon polygon) {
        List<Ring> shells = new ArrayList<>();
        shells.add(toShell(polygon));
        return new Polygon2D(shells);
    }

    public static Polygon2D create(MultiPolygon multiPolygon) {
        List<Ring> shells = new ArrayList<>();
        for (Polygon p : multiPolygon) {
            shells.add(toShell(p));
        }
        return new Polygon2D(shells);
    }

    private static Ring toShell(Polygon polygon) {
        Ring shell = new Ring(polygon.getPolygon().getX(), polygon.getPolygon().getY());
        List<Ring> holes = new ArrayList<>();
        for (LinearRing hole : polygon.getHoles()) {
            holes.add(new Ring(hole.getX(), hole.getY()));
        }
        shell.holes = holes;
        return shell;
    }

    public boolean contains(double x, double y) {
        for (Ring shell : shells) {
            if (shell.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    public Relation relate(double minX, double maxX, double minY, double maxY) {
        boolean anyInside = false;
        boolean anyOutside = false;
        for (Ring shell : shells) {
            Relation r = shell.relate(minX, maxX, minY, maxY);
            if (r == Relation.CELL_CROSSES_QUERY) {
                return Relation.CELL_CROSSES_QUERY;
            }
            if (r == Relation.CELL_INSIDE_QUERY) {
                anyInside = true;
            } else {
                anyOutside = true;
            }
        }
        if (anyInside && anyOutside) {
            return Relation.CELL_CROSSES_QUERY;
        }
        return anyInside ? Relation.CELL_INSIDE_QUERY : Relation.CELL_OUTSIDE_QUERY;
    }

    private static final class Ring {
        final double[] x;
        final double[] y;
        final double minX;
        final double maxX;
        final double minY;
        final double maxY;
        List<Ring> holes = List.of();

        Ring(double[] x, double[] y) {
            this.x = x;
            this.y = y;
            double mnx = Double.POSITIVE_INFINITY, mxx = Double.NEGATIVE_INFINITY;
            double mny = Double.POSITIVE_INFINITY, mxy = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < x.length; i++) {
                mnx = Math.min(mnx, x[i]);
                mxx = Math.max(mxx, x[i]);
                mny = Math.min(mny, y[i]);
                mxy = Math.max(mxy, y[i]);
            }
            this.minX = mnx;
            this.maxX = mxx;
            this.minY = mny;
            this.maxY = mxy;
        }

        boolean shellContains(double px, double py) {
            if (px < minX || px > maxX || py < minY || py > maxY) {
                return false;
            }
            return pointInRing(x, y, px, py);
        }

        boolean contains(double px, double py) {
            if (!shellContains(px, py)) {
                return false;
            }
            for (Ring hole : holes) {
                if (hole.shellContains(px, py)) {
                    return false;
                }
            }
            return true;
        }

        static boolean pointInRing(double[] rx, double[] ry, double px, double py) {
            boolean inside = false;
            int n = rx.length;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = rx[i], yi = ry[i];
                double xj = rx[j], yj = ry[j];
                if (((yi > py) != (yj > py))
                    && (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) {
                    inside = !inside;
                }
                if (onSegment(xi, yi, xj, yj, px, py)) {
                    return true;
                }
            }
            return inside;
        }

        static boolean onSegment(double x1, double y1, double x2, double y2, double px, double py) {
            double cross = (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1);
            if (Math.abs(cross) > 1e-9) {
                return false;
            }
            return px >= Math.min(x1, x2) - 1e-9 && px <= Math.max(x1, x2) + 1e-9
                && py >= Math.min(y1, y2) - 1e-9 && py <= Math.max(y1, y2) + 1e-9;
        }

        static boolean segmentsIntersect(double ax1, double ay1, double ax2, double ay2,
                                          double bx1, double by1, double bx2, double by2) {
            double d1 = cross(bx1, by1, bx2, by2, ax1, ay1);
            double d2 = cross(bx1, by1, bx2, by2, ax2, ay2);
            double d3 = cross(ax1, ay1, ax2, ay2, bx1, by1);
            double d4 = cross(ax1, ay1, ax2, ay2, bx2, by2);
            if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) {
                return true;
            }
            if (d1 == 0 && onSegment(ax1, ay1, ax2, ay2, bx1, by1)) {
                return true;
            }
            if (d2 == 0 && onSegment(ax1, ay1, ax2, ay2, bx2, by2)) {
                return true;
            }
            if (d3 == 0 && onSegment(bx1, by1, bx2, by2, ax1, ay1)) {
                return true;
            }
            if (d4 == 0 && onSegment(bx1, by1, bx2, by2, ax2, ay2)) {
                return true;
            }
            return false;
        }

        static double cross(double ox, double oy, double ax, double ay, double bx, double by) {
            return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
        }

        boolean ringIntersectsBox(double[] rx, double[] ry, double minX, double maxX, double minY, double maxY) {
            int n = rx.length;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                if (segmentIntersectsBox(rx[j], ry[j], rx[i], ry[i], minX, maxX, minY, maxY)) {
                    return true;
                }
            }
            return false;
        }

        static boolean segmentIntersectsBox(double x1, double y1, double x2, double y2,
                                             double minX, double maxX, double minY, double maxY) {
            if (Math.max(x1, x2) < minX || Math.min(x1, x2) > maxX || Math.max(y1, y2) < minY || Math.min(y1, y2) > maxY) {
                return false;
            }
            if ((x1 >= minX && x1 <= maxX && y1 >= minY && y1 <= maxY)
                || (x2 >= minX && x2 <= maxX && y2 >= minY && y2 <= maxY)) {
                return true;
            }
            return segmentsIntersect(x1, y1, x2, y2, minX, minY, maxX, minY)
                || segmentsIntersect(x1, y1, x2, y2, maxX, minY, maxX, maxY)
                || segmentsIntersect(x1, y1, x2, y2, maxX, maxY, minX, maxY)
                || segmentsIntersect(x1, y1, x2, y2, minX, maxY, minX, minY);
        }

        Relation relate(double bMinX, double bMaxX, double bMinY, double bMaxY) {
            if (bMaxX < minX || bMinX > maxX || bMaxY < minY || bMinY > maxY) {
                return Relation.CELL_OUTSIDE_QUERY;
            }
            if (ringIntersectsBox(x, y, bMinX, bMaxX, bMinY, bMaxY)) {
                return Relation.CELL_CROSSES_QUERY;
            }
            for (Ring hole : holes) {
                if (hole.ringIntersectsBox(hole.x, hole.y, bMinX, bMaxX, bMinY, bMaxY)) {
                    return Relation.CELL_CROSSES_QUERY;
                }
            }
            boolean cornerInside = contains(bMinX, bMinY);
            return cornerInside ? Relation.CELL_INSIDE_QUERY : Relation.CELL_OUTSIDE_QUERY;
        }
    }
}
