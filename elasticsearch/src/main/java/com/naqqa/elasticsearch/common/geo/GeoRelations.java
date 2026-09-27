package com.naqqa.elasticsearch.common.geo;

import com.naqqa.elasticsearch.common.geo.geometry.Circle;
import com.naqqa.elasticsearch.common.geo.geometry.MultiPolygon;
import com.naqqa.elasticsearch.common.geo.geometry.Polygon;
import com.naqqa.elasticsearch.common.geo.geometry.Rectangle;
import com.naqqa.elasticsearch.common.geo.shape.Polygon2D;
import com.naqqa.elasticsearch.common.geo.shape.Relation;

public final class GeoRelations {

    private GeoRelations() {
    }

    public static boolean pointInPolygon(Polygon polygon, double lon, double lat) {
        return com.naqqa.elasticsearch.common.geo.geometry.GeometryUtils.pointInPolygon(polygon, lon, lat);
    }

    public static boolean rectanglesIntersect(Rectangle a, Rectangle b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.getMaxY() < b.getMinY() || a.getMinY() > b.getMaxY()) {
            return false;
        }
        return lonRangesIntersect(a, b);
    }

    private static boolean lonRangesIntersect(Rectangle a, Rectangle b) {
        boolean aWraps = a.crossesDateline();
        boolean bWraps = b.crossesDateline();
        if (!aWraps && !bWraps) {
            return a.getMinX() <= b.getMaxX() && a.getMaxX() >= b.getMinX();
        }
        if (aWraps && bWraps) {
            return true;
        }
        Rectangle wrapping = aWraps ? a : b;
        Rectangle plain = aWraps ? b : a;
        return (plain.getMaxX() >= wrapping.getMinX()) || (plain.getMinX() <= wrapping.getMaxX());
    }

    public static boolean rectangleWithin(Rectangle inner, Rectangle outer) {
        if (inner.isEmpty() || outer.isEmpty()) {
            return false;
        }
        if (inner.getMinY() < outer.getMinY() || inner.getMaxY() > outer.getMaxY()) {
            return false;
        }
        if (!outer.crossesDateline()) {
            if (inner.crossesDateline()) {
                return false;
            }
            return inner.getMinX() >= outer.getMinX() && inner.getMaxX() <= outer.getMaxX();
        }
        if (inner.crossesDateline()) {
            return inner.getMinX() >= outer.getMinX() && inner.getMaxX() <= outer.getMaxX();
        }
        return inner.getMinX() >= outer.getMinX() || inner.getMaxX() <= outer.getMaxX();
    }

    public static boolean rectangleContains(Rectangle outer, Rectangle inner) {
        return rectangleWithin(inner, outer);
    }

    public static boolean rectangleDisjoint(Rectangle a, Rectangle b) {
        return !rectanglesIntersect(a, b);
    }

    public static Relation relate(Polygon polygon, Rectangle box) {
        return Polygon2D.create(polygon).relate(box.getMinX(), box.getMaxX(), box.getMinY(), box.getMaxY());
    }

    public static Relation relate(MultiPolygon multiPolygon, Rectangle box) {
        return Polygon2D.create(multiPolygon).relate(box.getMinX(), box.getMaxX(), box.getMinY(), box.getMaxY());
    }

    public static boolean intersects(Polygon polygon, Rectangle box) {
        return relate(polygon, box) != Relation.CELL_OUTSIDE_QUERY;
    }

    public static boolean disjoint(Polygon polygon, Rectangle box) {
        return relate(polygon, box) == Relation.CELL_OUTSIDE_QUERY;
    }

    public static boolean within(Rectangle box, Polygon polygon) {
        return relate(polygon, box) == Relation.CELL_INSIDE_QUERY;
    }

    public static boolean contains(Polygon polygon, Rectangle box) {
        return relate(polygon, box) == Relation.CELL_INSIDE_QUERY;
    }

    public static boolean pointInCircleGeo(Circle circle, double lon, double lat) {
        double d = GeoUtils.arcDistance(circle.getY(), circle.getX(), lat, lon);
        return d <= circle.getRadiusMeters();
    }

    public static boolean pointInCircleCartesian(Circle circle, double x, double y) {
        double dx = x - circle.getX();
        double dy = y - circle.getY();
        return dx * dx + dy * dy <= circle.getRadiusMeters() * circle.getRadiusMeters();
    }

    public static Relation relateGeo(Circle circle, Rectangle box) {
        return GeoUtils.relateBoxToCircle(box.getMinY(), box.getMaxY(), box.getMinX(), box.getMaxX(),
            circle.getY(), circle.getX(), circle.getRadiusMeters());
    }

    public static Relation relateCartesian(Circle circle, Rectangle box) {
        return GeoUtils.relateBoxToCartesianCircle(box.getMinX(), box.getMaxX(), box.getMinY(), box.getMaxY(),
            circle.getX(), circle.getY(), circle.getRadiusMeters());
    }

    public static boolean circleIntersectsRectangleGeo(Circle circle, Rectangle box) {
        return relateGeo(circle, box) != Relation.CELL_OUTSIDE_QUERY;
    }

    public static Rectangle circleToBBox(Circle circle) {
        return GeoUtils.circleToBBox(circle.getY(), circle.getX(), circle.getRadiusMeters());
    }
}
