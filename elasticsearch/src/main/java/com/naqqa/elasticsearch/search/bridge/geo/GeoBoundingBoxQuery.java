package com.naqqa.elasticsearch.search.bridge.geo;

import com.naqqa.elasticsearch.codec.points.Relation;

public final class GeoBoundingBoxQuery extends AbstractGeoPointQuery {

    private final double minLat;
    private final double maxLat;
    private final double minLon;
    private final double maxLon;

    public GeoBoundingBoxQuery(String field, double minLat, double maxLat, double minLon, double maxLon) {
        super(field);
        this.minLat = minLat;
        this.maxLat = maxLat;
        this.minLon = minLon;
        this.maxLon = maxLon;
    }

    private boolean crossesDateline() {
        return minLon > maxLon;
    }

    private boolean lonMatches(double lon) {
        return crossesDateline() ? (lon >= minLon || lon <= maxLon) : (lon >= minLon && lon <= maxLon);
    }

    @Override
    protected boolean matches(double lat, double lon) {
        return lat >= minLat && lat <= maxLat && lonMatches(lon);
    }

    @Override
    protected Relation compareCell(double cellMinLat, double cellMaxLat, double cellMinLon, double cellMaxLon) {
        if (cellMaxLat < minLat || cellMinLat > maxLat) {
            return Relation.CELL_OUTSIDE_QUERY;
        }
        if (crossesDateline()) {
            return Relation.CELL_CROSSES_QUERY;
        }
        if (cellMaxLon < minLon || cellMinLon > maxLon) {
            return Relation.CELL_OUTSIDE_QUERY;
        }
        boolean inside = cellMinLat >= minLat && cellMaxLat <= maxLat && cellMinLon >= minLon && cellMaxLon <= maxLon;
        return inside ? Relation.CELL_INSIDE_QUERY : Relation.CELL_CROSSES_QUERY;
    }

    @Override
    public String toString() {
        return "GeoBoundingBoxQuery(" + field + ", lat=[" + minLat + "," + maxLat + "], lon=[" + minLon + "," + maxLon + "])";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GeoBoundingBoxQuery q && field.equals(q.field) && minLat == q.minLat && maxLat == q.maxLat
            && minLon == q.minLon && maxLon == q.maxLon;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(field, minLat, maxLat, minLon, maxLon);
    }
}
