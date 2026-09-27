package com.naqqa.elasticsearch.common.geo.format;

import com.naqqa.elasticsearch.common.geo.geometry.Circle;
import com.naqqa.elasticsearch.common.geo.geometry.Geometry;
import com.naqqa.elasticsearch.common.geo.geometry.GeometryCollection;
import com.naqqa.elasticsearch.common.geo.geometry.GeometryVisitor;
import com.naqqa.elasticsearch.common.geo.geometry.Line;
import com.naqqa.elasticsearch.common.geo.geometry.LinearRing;
import com.naqqa.elasticsearch.common.geo.geometry.MultiLine;
import com.naqqa.elasticsearch.common.geo.geometry.MultiPoint;
import com.naqqa.elasticsearch.common.geo.geometry.MultiPolygon;
import com.naqqa.elasticsearch.common.geo.geometry.Point;
import com.naqqa.elasticsearch.common.geo.geometry.Polygon;
import com.naqqa.elasticsearch.common.geo.geometry.Rectangle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GeoJson {

    private GeoJson() {
    }

    public static Map<String, Object> toMap(Geometry geometry) {
        return geometry.visit(new GeometryVisitor<Map<String, Object>>() {
            @Override
            public Map<String, Object> visit(Point point) {
                return of(point.type(), point.isEmpty() ? List.of() : coord(point));
            }

            @Override
            public Map<String, Object> visit(MultiPoint multiPoint) {
                List<Object> coords = new ArrayList<>();
                for (Point p : multiPoint) {
                    coords.add(coord(p));
                }
                return of(multiPoint.type(), coords);
            }

            @Override
            public Map<String, Object> visit(Line line) {
                return of(line.type(), lineCoords(line));
            }

            @Override
            public Map<String, Object> visit(LinearRing ring) {
                return of(ring.type(), lineCoords(ring));
            }

            @Override
            public Map<String, Object> visit(MultiLine multiLine) {
                List<Object> coords = new ArrayList<>();
                for (Line l : multiLine) {
                    coords.add(lineCoords(l));
                }
                return of(multiLine.type(), coords);
            }

            @Override
            public Map<String, Object> visit(Polygon polygon) {
                return of(polygon.type(), polygonCoords(polygon));
            }

            @Override
            public Map<String, Object> visit(MultiPolygon multiPolygon) {
                List<Object> coords = new ArrayList<>();
                for (Polygon p : multiPolygon) {
                    coords.add(polygonCoords(p));
                }
                return of(multiPolygon.type(), coords);
            }

            @Override
            public Map<String, Object> visit(Rectangle rectangle) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "envelope");
                m.put("coordinates", List.of(
                    List.of(rectangle.getMinX(), rectangle.getMaxY()),
                    List.of(rectangle.getMaxX(), rectangle.getMinY())));
                return m;
            }

            @Override
            public Map<String, Object> visit(Circle circle) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "circle");
                m.put("coordinates", List.of(circle.getX(), circle.getY()));
                m.put("radius", circle.getRadiusMeters() + "m");
                return m;
            }

            @Override
            public Map<String, Object> visit(GeometryCollection collection) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "GeometryCollection");
                List<Object> geoms = new ArrayList<>();
                for (Geometry g : collection) {
                    geoms.add(toMap(g));
                }
                m.put("geometries", geoms);
                return m;
            }
        });
    }

    private static Map<String, Object> of(com.naqqa.elasticsearch.common.geo.geometry.ShapeType type, Object coords) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type.geoJsonName());
        m.put("coordinates", coords);
        return m;
    }

    private static List<Double> coord(Point p) {
        return p.hasZ() ? List.of(p.getX(), p.getY(), p.getZ()) : List.of(p.getX(), p.getY());
    }

    private static List<Object> lineCoords(Line line) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < line.length(); i++) {
            out.add(line.hasZ()
                ? List.of(line.getX(i), line.getY(i), line.getZ(i))
                : List.of(line.getX(i), line.getY(i)));
        }
        return out;
    }

    private static List<Object> polygonCoords(Polygon polygon) {
        List<Object> rings = new ArrayList<>();
        rings.add(lineCoords(polygon.getPolygon()));
        for (LinearRing hole : polygon.getHoles()) {
            rings.add(lineCoords(hole));
        }
        return rings;
    }

    @SuppressWarnings("unchecked")
    public static Geometry fromMap(Map<String, Object> map) {
        String type = String.valueOf(map.get("type"));
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.equals("ENVELOPE")) {
            List<List<Number>> c = (List<List<Number>>) map.get("coordinates");
            double minX = c.get(0).get(0).doubleValue();
            double maxY = c.get(0).get(1).doubleValue();
            double maxX = c.get(1).get(0).doubleValue();
            double minY = c.get(1).get(1).doubleValue();
            return new Rectangle(minX, maxX, maxY, minY);
        }
        if (upper.equals("CIRCLE")) {
            List<Number> c = (List<Number>) map.get("coordinates");
            double radius = parseRadius(map.get("radius"));
            return new Circle(c.get(0).doubleValue(), c.get(1).doubleValue(), radius);
        }
        if (upper.equals("GEOMETRYCOLLECTION")) {
            List<Map<String, Object>> geoms = (List<Map<String, Object>>) map.get("geometries");
            List<Geometry> out = new ArrayList<>();
            for (Map<String, Object> g : geoms) {
                out.add(fromMap(g));
            }
            return new GeometryCollection(out);
        }
        Object coordinates = map.get("coordinates");
        return switch (upper) {
            case "POINT" -> pointFrom((List<Number>) coordinates);
            case "MULTIPOINT" -> {
                List<Point> pts = new ArrayList<>();
                for (List<Number> c : (List<List<Number>>) coordinates) {
                    pts.add(pointFrom(c));
                }
                yield new MultiPoint(pts);
            }
            case "LINESTRING" -> lineFrom((List<List<Number>>) coordinates);
            case "MULTILINESTRING" -> {
                List<Line> lines = new ArrayList<>();
                for (List<List<Number>> c : (List<List<List<Number>>>) coordinates) {
                    lines.add(lineFrom(c));
                }
                yield new MultiLine(lines);
            }
            case "POLYGON" -> polygonFrom((List<List<List<Number>>>) coordinates);
            case "MULTIPOLYGON" -> {
                List<Polygon> polys = new ArrayList<>();
                for (List<List<List<Number>>> c : (List<List<List<List<Number>>>>) coordinates) {
                    polys.add(polygonFrom(c));
                }
                yield new MultiPolygon(polys);
            }
            default -> throw new IllegalArgumentException("unsupported geojson type [" + type + "]");
        };
    }

    private static double parseRadius(Object radius) {
        if (radius instanceof Number n) {
            return n.doubleValue();
        }
        String s = String.valueOf(radius).trim();
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '-')) {
            i++;
        }
        double value = Double.parseDouble(s.substring(0, i));
        String unit = s.substring(i).trim();
        return switch (unit.isEmpty() ? "m" : unit) {
            case "km" -> value * 1000.0;
            case "mi" -> value * 1609.344;
            case "ft" -> value * 0.3048;
            default -> value;
        };
    }

    private static Point pointFrom(List<Number> c) {
        return c.size() >= 3
            ? new Point(c.get(0).doubleValue(), c.get(1).doubleValue(), c.get(2).doubleValue())
            : new Point(c.get(0).doubleValue(), c.get(1).doubleValue());
    }

    private static Line lineFrom(List<List<Number>> coords) {
        double[] x = new double[coords.size()];
        double[] y = new double[coords.size()];
        boolean hasZ = coords.get(0).size() >= 3;
        double[] z = hasZ ? new double[coords.size()] : null;
        for (int i = 0; i < coords.size(); i++) {
            List<Number> c = coords.get(i);
            x[i] = c.get(0).doubleValue();
            y[i] = c.get(1).doubleValue();
            if (hasZ) {
                z[i] = c.get(2).doubleValue();
            }
        }
        return new Line(x, y, z);
    }

    private static Polygon polygonFrom(List<List<List<Number>>> rings) {
        Line shellLine = lineFrom(rings.get(0));
        LinearRing shell = new LinearRing(shellLine.getX(), shellLine.getY(), shellLine.getZ());
        List<LinearRing> holes = new ArrayList<>();
        for (int i = 1; i < rings.size(); i++) {
            Line hl = lineFrom(rings.get(i));
            holes.add(new LinearRing(hl.getX(), hl.getY(), hl.getZ()));
        }
        return new Polygon(shell, holes);
    }
}
