package com.naqqa.elasticsearch.search.bridge.geo;

import com.naqqa.elasticsearch.codec.points.Relation;
import com.naqqa.elasticsearch.common.geo.GeoDistance;
import com.naqqa.elasticsearch.common.geo.GeoUtils;
import com.naqqa.elasticsearch.common.geo.geometry.Circle;
import com.naqqa.elasticsearch.common.geo.geometry.Rectangle;

public final class GeoDistanceQuery extends AbstractGeoPointQuery {

    private final double centerLat;
    private final double centerLon;
    private final double radiusMeters;
    private final GeoDistance distanceType;

    public GeoDistanceQuery(String field, double centerLat, double centerLon, double radiusMeters, GeoDistance distanceType) {
        super(field);
        this.centerLat = centerLat;
        this.centerLon = centerLon;
        this.radiusMeters = radiusMeters;
        this.distanceType = distanceType;
    }

    @Override
    protected boolean matches(double lat, double lon) {
        double distance = distanceType == GeoDistance.PLANE
            ? GeoUtils.planeDistance(centerLat, centerLon, lat, lon)
            : GeoUtils.arcDistance(centerLat, centerLon, lat, lon);
        return distance <= radiusMeters;
    }

    @Override
    protected Relation compareCell(double cellMinLat, double cellMaxLat, double cellMinLon, double cellMaxLon) {
        Rectangle box = new Rectangle(cellMinLon, cellMaxLon, cellMaxLat, cellMinLat);
        Circle circle = new Circle(centerLon, centerLat, radiusMeters);
        com.naqqa.elasticsearch.common.geo.shape.Relation r = distanceType == GeoDistance.PLANE
            ? com.naqqa.elasticsearch.common.geo.GeoRelations.relateCartesian(circle, box)
            : com.naqqa.elasticsearch.common.geo.GeoRelations.relateGeo(circle, box);
        return switch (r) {
            case CELL_INSIDE_QUERY -> Relation.CELL_INSIDE_QUERY;
            case CELL_OUTSIDE_QUERY -> Relation.CELL_OUTSIDE_QUERY;
            case CELL_CROSSES_QUERY -> Relation.CELL_CROSSES_QUERY;
        };
    }

    @Override
    public String toString() {
        return "GeoDistanceQuery(" + field + ", center=(" + centerLat + "," + centerLon + "), radius=" + radiusMeters + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GeoDistanceQuery q && field.equals(q.field) && centerLat == q.centerLat && centerLon == q.centerLon
            && radiusMeters == q.radiusMeters && distanceType == q.distanceType;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(field, centerLat, centerLon, radiusMeters, distanceType);
    }
}
