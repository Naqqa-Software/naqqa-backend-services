package com.naqqa.elasticsearch.common.geo;

import com.naqqa.elasticsearch.common.geo.geometry.LinearRing;
import com.naqqa.elasticsearch.common.geo.geometry.Polygon;

import java.util.ArrayList;
import java.util.List;

public final class Tessellator {

    private Tessellator() {
    }

    public record Triangle(double ax, double ay, boolean abFromPolygon,
                            double bx, double by, boolean bcFromPolygon,
                            double cx, double cy, boolean caFromPolygon) {

        public double area() {
            return Math.abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2.0;
        }
    }

    private static final class Node {
        double x;
        double y;
        boolean edgeOriginal = true;
        Node prev;
        Node next;

        Node(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    public static List<Triangle> tessellate(Polygon polygon) {
        List<Triangle> triangles = new ArrayList<>();
        if (polygon.isEmpty()) {
            return triangles;
        }
        Node outer = buildRing(polygon.getPolygon(), true);
        for (LinearRing hole : polygon.getHoles()) {
            Node holeNode = buildRing(hole, false);
            outer = eliminateHole(outer, holeNode);
        }
        earClip(outer, triangles);
        return triangles;
    }

    private static Node buildRing(LinearRing ring, boolean forceCcw) {
        int n = ring.length() - 1;
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = ring.getX(i);
            ys[i] = ring.getY(i);
        }
        double area = signedArea(xs, ys);
        boolean isCcw = area > 0;
        if (isCcw != forceCcw) {
            reverse(xs, ys);
        }
        Node first = new Node(xs[0], ys[0]);
        Node prev = first;
        for (int i = 1; i < n; i++) {
            Node node = new Node(xs[i], ys[i]);
            prev.next = node;
            node.prev = prev;
            prev = node;
        }
        prev.next = first;
        first.prev = prev;
        return first;
    }

    private static void reverse(double[] xs, double[] ys) {
        int n = xs.length;
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            double tx = xs[i];
            xs[i] = xs[j];
            xs[j] = tx;
            double ty = ys[i];
            ys[i] = ys[j];
            ys[j] = ty;
        }
    }

    private static double signedArea(double[] xs, double[] ys) {
        double sum = 0;
        int n = xs.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return sum / 2.0;
    }

    private static Node eliminateHole(Node outer, Node hole) {
        Node leftmost = hole;
        Node p = hole.next;
        while (p != hole) {
            if (p.x < leftmost.x) {
                leftmost = p;
            }
            p = p.next;
        }
        Node bridge = findVisibleBridge(outer, leftmost);
        Node bridgeReverse = splitPolygon(bridge, leftmost);
        bridge.edgeOriginal = false;
        bridgeReverse.edgeOriginal = false;
        return bridgeReverse;
    }

    private static Node findVisibleBridge(Node outer, Node holePoint) {
        Node best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        Node p = outer;
        do {
            if (isVisible(holePoint, p, outer)) {
                double dx = p.x - holePoint.x;
                double dy = p.y - holePoint.y;
                double d = dx * dx + dy * dy;
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
            }
            p = p.next;
        } while (p != outer);
        if (best == null) {
            throw new IllegalArgumentException("could not find a visible bridge for polygon hole");
        }
        return best;
    }

    private static boolean isVisible(Node from, Node to, Node ringStart) {
        if (from == to) {
            return false;
        }
        Node p = ringStart;
        do {
            Node q = p.next;
            if (p != from && q != from && p != to && q != to
                && segmentsProperlyIntersect(from.x, from.y, to.x, to.y, p.x, p.y, q.x, q.y)) {
                return false;
            }
            p = p.next;
        } while (p != ringStart);
        return true;
    }

    private static double cross(double ox, double oy, double ax, double ay, double bx, double by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    private static boolean segmentsProperlyIntersect(double ax1, double ay1, double ax2, double ay2,
                                                       double bx1, double by1, double bx2, double by2) {
        double d1 = cross(bx1, by1, bx2, by2, ax1, ay1);
        double d2 = cross(bx1, by1, bx2, by2, ax2, ay2);
        double d3 = cross(ax1, ay1, ax2, ay2, bx1, by1);
        double d4 = cross(ax1, ay1, ax2, ay2, bx2, by2);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static Node splitPolygon(Node a, Node b) {
        Node a2 = new Node(a.x, a.y);
        Node b2 = new Node(b.x, b.y);
        a2.edgeOriginal = a.edgeOriginal;
        b2.edgeOriginal = b.edgeOriginal;
        Node an = a.next;
        Node bp = b.prev;

        a.next = b;
        b.prev = a;

        a2.next = an;
        an.prev = a2;

        b2.next = a2;
        a2.prev = b2;

        bp.next = b2;
        b2.prev = bp;

        return b2;
    }

    private static void earClip(Node start, List<Triangle> out) {
        Node ring = filterDuplicates(start);
        if (ring == null) {
            return;
        }
        Node p = ring;
        int guard = 0;
        int remaining = count(ring);
        while (remaining > 3) {
            boolean earFound = false;
            Node search = p;
            int scanned = 0;
            while (scanned < remaining) {
                if (isEar(search)) {
                    Node prev = search.prev;
                    Node next = search.next;
                    emit(prev, search, next, false, out);
                    prev.next = next;
                    next.prev = prev;
                    prev.edgeOriginal = false;
                    p = next;
                    remaining--;
                    earFound = true;
                    break;
                }
                search = search.next;
                scanned++;
            }
            if (!earFound) {
                guard++;
                if (guard > remaining + 5) {
                    break;
                }
                p = p.next;
            } else {
                guard = 0;
            }
        }
        if (remaining >= 3) {
            emit(p, p.next, p.next.next, true, out);
        }
    }

    private static Node filterDuplicates(Node start) {
        Node p = start;
        boolean removed;
        do {
            removed = false;
            if (p.next != p && Double.compare(p.x, p.next.x) == 0 && Double.compare(p.y, p.next.y) == 0) {
                Node next = p.next;
                p.next = next.next;
                next.next.prev = p;
                if (start == next) {
                    start = p;
                }
                removed = true;
            }
            p = p.next;
        } while (removed || p != start);
        return start;
    }

    private static int count(Node start) {
        int n = 0;
        Node p = start;
        do {
            n++;
            p = p.next;
        } while (p != start);
        return n;
    }

    private static boolean isEar(Node node) {
        Node a = node.prev;
        Node b = node;
        Node c = node.next;
        double area = cross(a.x, a.y, b.x, b.y, c.x, c.y);
        if (area <= 0) {
            return false;
        }
        Node p = c.next;
        while (p != a) {
            if (!samePoint(p, a) && !samePoint(p, b) && !samePoint(p, c)
                && pointInTriangle(a.x, a.y, b.x, b.y, c.x, c.y, p.x, p.y)) {
                return false;
            }
            p = p.next;
        }
        return true;
    }

    private static boolean samePoint(Node p, Node q) {
        return Double.compare(p.x, q.x) == 0 && Double.compare(p.y, q.y) == 0;
    }

    private static boolean pointInTriangle(double ax, double ay, double bx, double by, double cx, double cy,
                                            double px, double py) {
        double d1 = cross(ax, ay, bx, by, px, py);
        double d2 = cross(bx, by, cx, cy, px, py);
        double d3 = cross(cx, cy, ax, ay, px, py);
        boolean hasNeg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean hasPos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(hasNeg && hasPos);
    }

    private static void emit(Node a, Node b, Node c, boolean lastTriangle, List<Triangle> out) {
        boolean ca = lastTriangle && c.edgeOriginal;
        out.add(new Triangle(a.x, a.y, a.edgeOriginal, b.x, b.y, b.edgeOriginal, c.x, c.y, ca));
    }
}
