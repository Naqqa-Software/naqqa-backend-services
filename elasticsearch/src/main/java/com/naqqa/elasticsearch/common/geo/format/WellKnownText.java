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
import java.util.List;
import java.util.Locale;

public final class WellKnownText {

    private WellKnownText() {
    }

    public static String toWKT(Geometry geometry) {
        StringBuilder sb = new StringBuilder();
        geometry.visit(new GeometryVisitor<Void>() {
            @Override
            public Void visit(Point point) {
                sb.append("POINT ");
                if (point.isEmpty()) {
                    sb.append("EMPTY");
                } else {
                    sb.append('(');
                    coord(sb, point);
                    sb.append(')');
                }
                return null;
            }

            @Override
            public Void visit(MultiPoint multiPoint) {
                sb.append("MULTIPOINT ");
                if (multiPoint.isEmpty()) {
                    sb.append("EMPTY");
                    return null;
                }
                sb.append('(');
                for (int i = 0; i < multiPoint.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('(');
                    coord(sb, multiPoint.get(i));
                    sb.append(')');
                }
                sb.append(')');
                return null;
            }

            @Override
            public Void visit(Line line) {
                sb.append("LINESTRING ");
                writeLine(sb, line);
                return null;
            }

            @Override
            public Void visit(LinearRing ring) {
                sb.append("LINEARRING ");
                writeLine(sb, ring);
                return null;
            }

            @Override
            public Void visit(MultiLine multiLine) {
                sb.append("MULTILINESTRING ");
                if (multiLine.isEmpty()) {
                    sb.append("EMPTY");
                    return null;
                }
                sb.append('(');
                for (int i = 0; i < multiLine.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    writeLineBody(sb, multiLine.get(i));
                }
                sb.append(')');
                return null;
            }

            @Override
            public Void visit(Polygon polygon) {
                sb.append("POLYGON ");
                writePolygon(sb, polygon);
                return null;
            }

            @Override
            public Void visit(MultiPolygon multiPolygon) {
                sb.append("MULTIPOLYGON ");
                if (multiPolygon.isEmpty()) {
                    sb.append("EMPTY");
                    return null;
                }
                sb.append('(');
                for (int i = 0; i < multiPolygon.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    writePolygonBody(sb, multiPolygon.get(i));
                }
                sb.append(')');
                return null;
            }

            @Override
            public Void visit(Rectangle rectangle) {
                sb.append("BBOX ");
                if (rectangle.isEmpty()) {
                    sb.append("EMPTY");
                    return null;
                }
                sb.append('(').append(fmt(rectangle.getMinX())).append(", ").append(fmt(rectangle.getMaxX()))
                    .append(", ").append(fmt(rectangle.getMaxY())).append(", ").append(fmt(rectangle.getMinY())).append(')');
                return null;
            }

            @Override
            public Void visit(Circle circle) {
                sb.append("CIRCLE ");
                if (circle.isEmpty()) {
                    sb.append("EMPTY");
                    return null;
                }
                sb.append('(').append(fmt(circle.getX())).append(' ').append(fmt(circle.getY()))
                    .append(' ').append(fmt(circle.getRadiusMeters())).append(')');
                return null;
            }

            @Override
            public Void visit(GeometryCollection collection) {
                sb.append("GEOMETRYCOLLECTION ");
                if (collection.isEmpty()) {
                    sb.append("EMPTY");
                    return null;
                }
                sb.append('(');
                for (int i = 0; i < collection.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(toWKT(collection.get(i)));
                }
                sb.append(')');
                return null;
            }
        });
        return sb.toString();
    }

    private static void writeLine(StringBuilder sb, Line line) {
        if (line.isEmpty()) {
            sb.append("EMPTY");
            return;
        }
        writeLineBody(sb, line);
    }

    private static void writeLineBody(StringBuilder sb, Line line) {
        sb.append('(');
        for (int i = 0; i < line.length(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(fmt(line.getX(i))).append(' ').append(fmt(line.getY(i)));
        }
        sb.append(')');
    }

    private static void writePolygon(StringBuilder sb, Polygon polygon) {
        if (polygon.isEmpty()) {
            sb.append("EMPTY");
            return;
        }
        writePolygonBody(sb, polygon);
    }

    private static void writePolygonBody(StringBuilder sb, Polygon polygon) {
        sb.append('(');
        writeLineBody(sb, polygon.getPolygon());
        for (LinearRing hole : polygon.getHoles()) {
            sb.append(", ");
            writeLineBody(sb, hole);
        }
        sb.append(')');
    }

    private static void coord(StringBuilder sb, Point p) {
        sb.append(fmt(p.getX())).append(' ').append(fmt(p.getY()));
    }

    private static String fmt(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.format(Locale.ROOT, "%.1f", d);
        }
        return String.valueOf(d);
    }

    public static Geometry fromWKT(String wkt) {
        return new Parser(wkt).parseGeometry();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.pos = 0;
        }

        Geometry parseGeometry() {
            skipWs();
            String type = nextWord();
            Geometry g = parseByType(type);
            skipWs();
            if (pos != s.length()) {
                throw new IllegalArgumentException("unexpected trailing content in WKT: " + s);
            }
            return g;
        }

        private Geometry parseByType(String type) {
            switch (type.toUpperCase(Locale.ROOT)) {
                case "POINT":
                    return parsePoint();
                case "MULTIPOINT":
                    return parseMultiPoint();
                case "LINESTRING":
                    return parseLineString();
                case "LINEARRING":
                    return parseLinearRing();
                case "MULTILINESTRING":
                    return parseMultiLineString();
                case "POLYGON":
                    return parsePolygon();
                case "MULTIPOLYGON":
                    return parseMultiPolygon();
                case "GEOMETRYCOLLECTION":
                    return parseGeometryCollection();
                case "BBOX", "ENVELOPE":
                    return parseBBox();
                case "CIRCLE":
                    return parseCircle();
                default:
                    throw new IllegalArgumentException("unknown geometry type [" + type + "]");
            }
        }

        private Geometry parsePoint() {
            skipWs();
            if (tryConsumeEmpty()) {
                return Point.EMPTY;
            }
            consume('(');
            double[] c = coordinate();
            consume(')');
            return c.length == 3 ? new Point(c[0], c[1], c[2]) : new Point(c[0], c[1]);
        }

        private Geometry parseMultiPoint() {
            skipWs();
            if (tryConsumeEmpty()) {
                return MultiPoint.EMPTY;
            }
            consume('(');
            List<Point> pts = new ArrayList<>();
            do {
                skipWs();
                boolean paren = peek() == '(';
                if (paren) {
                    consume('(');
                }
                double[] c = coordinate();
                if (paren) {
                    consume(')');
                }
                pts.add(c.length == 3 ? new Point(c[0], c[1], c[2]) : new Point(c[0], c[1]));
            } while (tryConsume(','));
            consume(')');
            return new MultiPoint(pts);
        }

        private Line parseLineBody() {
            consume('(');
            List<double[]> coords = new ArrayList<>();
            do {
                coords.add(coordinate());
            } while (tryConsume(','));
            consume(')');
            return toLine(coords);
        }

        private Line toLine(List<double[]> coords) {
            boolean hasZ = coords.get(0).length == 3;
            double[] x = new double[coords.size()];
            double[] y = new double[coords.size()];
            double[] z = hasZ ? new double[coords.size()] : null;
            for (int i = 0; i < coords.size(); i++) {
                x[i] = coords.get(i)[0];
                y[i] = coords.get(i)[1];
                if (hasZ) {
                    z[i] = coords.get(i)[2];
                }
            }
            return new Line(x, y, z);
        }

        private Geometry parseLineString() {
            skipWs();
            if (tryConsumeEmpty()) {
                return Line.EMPTY;
            }
            return parseLineBody();
        }

        private Geometry parseLinearRing() {
            skipWs();
            if (tryConsumeEmpty()) {
                return LinearRing.EMPTY;
            }
            Line l = parseLineBody();
            return new LinearRing(l.getX(), l.getY(), l.getZ());
        }

        private Geometry parseMultiLineString() {
            skipWs();
            if (tryConsumeEmpty()) {
                return MultiLine.EMPTY;
            }
            consume('(');
            List<Line> lines = new ArrayList<>();
            do {
                skipWs();
                lines.add(parseLineBody());
            } while (tryConsume(','));
            consume(')');
            return new MultiLine(lines);
        }

        private Polygon parsePolygonBody() {
            consume('(');
            Line shell = parseLineBody();
            LinearRing shellRing = new LinearRing(shell.getX(), shell.getY(), shell.getZ());
            List<LinearRing> holes = new ArrayList<>();
            while (tryConsume(',')) {
                skipWs();
                Line hole = parseLineBody();
                holes.add(new LinearRing(hole.getX(), hole.getY(), hole.getZ()));
            }
            consume(')');
            return new Polygon(shellRing, holes);
        }

        private Geometry parsePolygon() {
            skipWs();
            if (tryConsumeEmpty()) {
                return Polygon.EMPTY;
            }
            return parsePolygonBody();
        }

        private Geometry parseMultiPolygon() {
            skipWs();
            if (tryConsumeEmpty()) {
                return MultiPolygon.EMPTY;
            }
            consume('(');
            List<Polygon> polys = new ArrayList<>();
            do {
                skipWs();
                polys.add(parsePolygonBody());
            } while (tryConsume(','));
            consume(')');
            return new MultiPolygon(polys);
        }

        private Geometry parseGeometryCollection() {
            skipWs();
            if (tryConsumeEmpty()) {
                return GeometryCollection.EMPTY;
            }
            consume('(');
            List<Geometry> geoms = new ArrayList<>();
            do {
                skipWs();
                String type = nextWord();
                geoms.add(parseByType(type));
                skipWs();
            } while (tryConsume(','));
            consume(')');
            return new GeometryCollection(geoms);
        }

        private Geometry parseBBox() {
            skipWs();
            if (tryConsumeEmpty()) {
                return Rectangle.EMPTY;
            }
            consume('(');
            double minX = number();
            tryConsume(',');
            double maxX = number();
            tryConsume(',');
            double maxY = number();
            tryConsume(',');
            double minY = number();
            consume(')');
            return new Rectangle(minX, maxX, maxY, minY);
        }

        private Geometry parseCircle() {
            skipWs();
            if (tryConsumeEmpty()) {
                return Circle.EMPTY;
            }
            consume('(');
            double x = number();
            skipWs();
            double y = number();
            skipWs();
            double r = number();
            consume(')');
            return new Circle(x, y, r);
        }

        private double[] coordinate() {
            skipWs();
            double x = number();
            skipWs();
            double y = number();
            skipWs();
            if (isNumberStart()) {
                double z = number();
                return new double[] {x, y, z};
            }
            return new double[] {x, y};
        }

        private boolean isNumberStart() {
            if (pos >= s.length()) {
                return false;
            }
            char c = s.charAt(pos);
            return c == '-' || c == '+' || Character.isDigit(c) || c == '.';
        }

        private double number() {
            skipWs();
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                    pos++;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("expected number at position " + pos + " in [" + s + "]");
            }
            return Double.parseDouble(s.substring(start, pos));
        }

        private boolean tryConsumeEmpty() {
            skipWs();
            int save = pos;
            String w = tryWord();
            if (w != null && w.equalsIgnoreCase("EMPTY")) {
                return true;
            }
            pos = save;
            return false;
        }

        private String tryWord() {
            skipWs();
            int start = pos;
            while (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                pos++;
            }
            if (pos == start) {
                return null;
            }
            return s.substring(start, pos);
        }

        private String nextWord() {
            String w = tryWord();
            if (w == null) {
                throw new IllegalArgumentException("expected identifier at position " + pos + " in [" + s + "]");
            }
            return w;
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            skipWs();
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void consume(char c) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at position " + pos + " in [" + s + "]");
            }
            pos++;
        }

        private boolean tryConsume(char c) {
            skipWs();
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }
    }
}
