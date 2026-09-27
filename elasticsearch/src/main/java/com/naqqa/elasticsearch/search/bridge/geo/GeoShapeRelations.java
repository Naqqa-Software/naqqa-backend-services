package com.naqqa.elasticsearch.search.bridge.geo;

import com.naqqa.elasticsearch.common.geo.GeoRelations;
import com.naqqa.elasticsearch.common.geo.geometry.Circle;
import com.naqqa.elasticsearch.common.geo.geometry.Geometry;
import com.naqqa.elasticsearch.common.geo.geometry.GeometryCollection;
import com.naqqa.elasticsearch.common.geo.geometry.GeometryUtils;
import com.naqqa.elasticsearch.common.geo.geometry.MultiPoint;
import com.naqqa.elasticsearch.common.geo.geometry.MultiPolygon;
import com.naqqa.elasticsearch.common.geo.geometry.Point;
import com.naqqa.elasticsearch.common.geo.geometry.Polygon;
import com.naqqa.elasticsearch.common.geo.geometry.Rectangle;

public final class GeoShapeRelations {

    public enum Kind { INTERSECTS, DISJOINT, WITHIN, CONTAINS }

    private GeoShapeRelations() {
    }

    public static boolean test(Geometry doc, Geometry query, Kind kind) {
        if (doc == null || query == null || doc.isEmpty() || query.isEmpty()) {
            return kind == Kind.DISJOINT;
        }
        if (doc instanceof Point p) {
            boolean inside = pointInGeometry(query, p.getX(), p.getY());
            return switch (kind) {
                case INTERSECTS -> inside;
                case DISJOINT -> !inside;
                case WITHIN -> inside;
                case CONTAINS -> query instanceof Point qp && qp.getX() == p.getX() && qp.getY() == p.getY();
            };
        }
        if (query instanceof Point p) {
            boolean inside = pointInGeometry(doc, p.getX(), p.getY());
            return switch (kind) {
                case INTERSECTS -> inside;
                case DISJOINT -> !inside;
                case WITHIN -> false;
                case CONTAINS -> inside;
            };
        }
        Rectangle docBox = GeometryUtils.boundingBox(doc);
        Rectangle queryBox = GeometryUtils.boundingBox(query);
        if (docBox == null || queryBox == null) {
            return kind == Kind.DISJOINT;
        }
        boolean bboxIntersect = GeoRelations.rectanglesIntersect(docBox, queryBox);
        return switch (kind) {
            case INTERSECTS -> bboxIntersect && refinedIntersects(doc, query, docBox, queryBox);
            case DISJOINT -> !(bboxIntersect && refinedIntersects(doc, query, docBox, queryBox));
            case WITHIN -> GeoRelations.rectangleWithin(docBox, queryBox) && refinedWithin(doc, query);
            case CONTAINS -> GeoRelations.rectangleContains(docBox, queryBox) && refinedWithin(query, doc);
        };
    }

    private static boolean refinedIntersects(Geometry doc, Geometry query, Rectangle docBox, Rectangle queryBox) {
        Polygon docPolygon = asSinglePolygon(doc);
        if (docPolygon != null) {
            return GeoRelations.intersects(docPolygon, queryBox);
        }
        Polygon queryPolygon = asSinglePolygon(query);
        if (queryPolygon != null) {
            return GeoRelations.intersects(queryPolygon, docBox);
        }
        return true;
    }

    private static boolean refinedWithin(Geometry inner, Geometry outer) {
        Polygon outerPolygon = asSinglePolygon(outer);
        if (outerPolygon == null) {
            return true;
        }
        for (double[] vertex : sampleVertices(inner)) {
            if (!GeometryUtils.pointInPolygon(outerPolygon, vertex[0], vertex[1])) {
                return false;
            }
        }
        return true;
    }

    private static Polygon asSinglePolygon(Geometry g) {
        if (g instanceof Polygon p) {
            return p;
        }
        if (g instanceof MultiPolygon mp && mp.size() == 1) {
            return mp.get(0);
        }
        return null;
    }

    private static java.util.List<double[]> sampleVertices(Geometry g) {
        java.util.List<double[]> out = new java.util.ArrayList<>();
        switch (g) {
            case Point p -> out.add(new double[] {p.getX(), p.getY()});
            case Polygon p -> {
                var ring = p.getPolygon();
                for (int i = 0; i < ring.length(); i++) {
                    out.add(new double[] {ring.getX(i), ring.getY(i)});
                }
            }
            case MultiPolygon mp -> {
                for (Polygon p : mp) {
                    out.addAll(sampleVertices(p));
                }
            }
            case Rectangle r -> {
                out.add(new double[] {r.getMinX(), r.getMinY()});
                out.add(new double[] {r.getMaxX(), r.getMinY()});
                out.add(new double[] {r.getMaxX(), r.getMaxY()});
                out.add(new double[] {r.getMinX(), r.getMaxY()});
            }
            default -> {
                Rectangle box = GeometryUtils.boundingBox(g);
                if (box != null) {
                    out.addAll(sampleVertices(box));
                }
            }
        }
        return out;
    }

    public static boolean pointInGeometry(Geometry geometry, double lon, double lat) {
        return switch (geometry) {
            case Point p -> p.getX() == lon && p.getY() == lat;
            case MultiPoint mp -> {
                boolean found = false;
                for (Point p : mp) {
                    if (p.getX() == lon && p.getY() == lat) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            case Polygon p -> GeometryUtils.pointInPolygon(p, lon, lat);
            case MultiPolygon mp -> {
                boolean found = false;
                for (Polygon p : mp) {
                    if (GeometryUtils.pointInPolygon(p, lon, lat)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            case Rectangle r -> GeometryUtils.rectangleContains(r, lon, lat);
            case Circle c -> GeoRelations.pointInCircleGeo(c, lon, lat);
            case GeometryCollection gc -> {
                boolean found = false;
                for (Geometry sub : gc) {
                    if (pointInGeometry(sub, lon, lat)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }
            default -> false;
        };
    }
}
