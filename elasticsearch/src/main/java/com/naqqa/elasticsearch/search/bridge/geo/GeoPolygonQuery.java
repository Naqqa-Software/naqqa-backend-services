package com.naqqa.elasticsearch.search.bridge.geo;

import com.naqqa.elasticsearch.codec.points.Relation;
import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.common.geo.GeoRelations;
import com.naqqa.elasticsearch.common.geo.geometry.LinearRing;
import com.naqqa.elasticsearch.common.geo.geometry.Polygon;
import com.naqqa.elasticsearch.common.geo.geometry.Rectangle;

import java.util.List;

public final class GeoPolygonQuery extends AbstractGeoPointQuery {

    private final List<GeoPoint> points;
    private final Polygon polygon;

    public GeoPolygonQuery(String field, List<GeoPoint> points) {
        super(field);
        this.points = List.copyOf(points);
        this.polygon = buildPolygon(this.points);
    }

    private static Polygon buildPolygon(List<GeoPoint> points) {
        int n = points.size();
        boolean closed = n > 0 && points.get(0).lat() == points.get(n - 1).lat() && points.get(0).lon() == points.get(n - 1).lon();
        int size = closed ? n : n + 1;
        double[] x = new double[size];
        double[] y = new double[size];
        for (int i = 0; i < n; i++) {
            x[i] = points.get(i).lon();
            y[i] = points.get(i).lat();
        }
        if (!closed) {
            x[n] = points.get(0).lon();
            y[n] = points.get(0).lat();
        }
        return new Polygon(new LinearRing(x, y));
    }

    @Override
    protected boolean matches(double lat, double lon) {
        return GeoRelations.pointInPolygon(polygon, lon, lat);
    }

    @Override
    protected Relation compareCell(double cellMinLat, double cellMaxLat, double cellMinLon, double cellMaxLon) {
        Rectangle box = new Rectangle(cellMinLon, cellMaxLon, cellMaxLat, cellMinLat);
        com.naqqa.elasticsearch.common.geo.shape.Relation r = GeoRelations.relate(polygon, box);
        return switch (r) {
            case CELL_INSIDE_QUERY -> Relation.CELL_INSIDE_QUERY;
            case CELL_OUTSIDE_QUERY -> Relation.CELL_OUTSIDE_QUERY;
            case CELL_CROSSES_QUERY -> Relation.CELL_CROSSES_QUERY;
        };
    }

    @Override
    public String toString() {
        return "GeoPolygonQuery(" + field + ", points=" + points.size() + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GeoPolygonQuery q && field.equals(q.field) && points.equals(q.points);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(field, points);
    }
}
