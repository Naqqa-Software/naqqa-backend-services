package com.naqqa.elasticsearch.common.geo.geometry;

import java.util.Locale;

public enum ShapeType {
    POINT("POINT", "Point"),
    MULTIPOINT("MULTIPOINT", "MultiPoint"),
    LINESTRING("LINESTRING", "LineString"),
    MULTILINESTRING("MULTILINESTRING", "MultiLineString"),
    POLYGON("POLYGON", "Polygon"),
    MULTIPOLYGON("MULTIPOLYGON", "MultiPolygon"),
    GEOMETRYCOLLECTION("GEOMETRYCOLLECTION", "GeometryCollection"),
    LINEARRING("LINEARRING", "LinearRing"),
    ENVELOPE("BBOX", "Envelope"),
    CIRCLE("CIRCLE", "Circle");

    private final String wktName;
    private final String geoJsonName;

    ShapeType(String wktName, String geoJsonName) {
        this.wktName = wktName;
        this.geoJsonName = geoJsonName;
    }

    public String wktName() {
        return wktName;
    }

    public String geoJsonName() {
        return geoJsonName;
    }

    public static ShapeType forName(String name) {
        String n = name.trim().toUpperCase(Locale.ROOT);
        return switch (n) {
            case "POINT" -> POINT;
            case "MULTIPOINT" -> MULTIPOINT;
            case "LINESTRING" -> LINESTRING;
            case "MULTILINESTRING" -> MULTILINESTRING;
            case "POLYGON" -> POLYGON;
            case "MULTIPOLYGON" -> MULTIPOLYGON;
            case "GEOMETRYCOLLECTION" -> GEOMETRYCOLLECTION;
            case "LINEARRING" -> LINEARRING;
            case "ENVELOPE", "BBOX" -> ENVELOPE;
            case "CIRCLE" -> CIRCLE;
            default -> throw new IllegalArgumentException("unknown geometry type [" + name + "]");
        };
    }
}
