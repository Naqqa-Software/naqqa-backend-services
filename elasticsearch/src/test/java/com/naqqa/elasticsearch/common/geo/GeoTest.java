package com.naqqa.elasticsearch.common.geo;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.common.geo.format.GeoJson;
import com.naqqa.elasticsearch.common.geo.format.WellKnownText;
import com.naqqa.elasticsearch.common.geo.geometry.Geometry;
import com.naqqa.elasticsearch.common.geo.geometry.GeometryUtils;
import com.naqqa.elasticsearch.common.geo.geometry.LinearRing;
import com.naqqa.elasticsearch.common.geo.geometry.Point;
import com.naqqa.elasticsearch.common.geo.geometry.Polygon;
import com.naqqa.elasticsearch.common.geo.geometry.Rectangle;
import com.naqqa.elasticsearch.test.Test;
import java.util.List;
import java.util.Map;

public class GeoTest {

    @Test
    public void geohashKnownValue() {
        String hash = Geohash.stringEncode(10.40744, 57.64911, 11);
        assertEquals("u4pruydqqvj", hash);
        GeoPoint decoded = Geohash.decode(hash);
        assertTrue(Math.abs(decoded.lat() - 57.64911) < 1e-3);
        assertTrue(Math.abs(decoded.lon() - 10.40744) < 1e-3);
    }

    @Test
    public void geohashNeighborsAndBbox() {
        String hash = Geohash.stringEncode(10.0, 50.0, 6);
        List<String> neighbors = Geohash.getNeighbors(hash);
        assertEquals(8, neighbors.size());
        for (String n : neighbors) {
            assertTrue(Geohash.isValid(n));
            assertEquals(6, n.length());
        }
        Rectangle bbox = Geohash.toBoundingBox(hash);
        assertTrue(bbox.getMinLon() <= 10.0 && bbox.getMaxLon() >= 10.0);
        assertTrue(bbox.getMinLat() <= 50.0 && bbox.getMaxLat() >= 50.0);
    }

    @Test
    public void geotileEncodeDecodeRoundTrip() {
        for (int z = 1; z <= 15; z++) {
            long hash = GeoTileUtils.longEncode(10.5, 50.5, z);
            Rectangle bbox = GeoTileUtils.toBoundingBox(hash);
            assertTrue(bbox.getMinLon() <= 10.5 && bbox.getMaxLon() >= 10.5, "z=" + z);
            assertTrue(bbox.getMinLat() <= 50.5 && bbox.getMaxLat() >= 50.5, "z=" + z);
            String s = GeoTileUtils.stringEncode(hash);
            assertEquals(hash, GeoTileUtils.longEncode(s));
        }
    }

    @Test
    public void haversineArcAndPlaneDistance() {
        double londonLat = 51.5074, londonLon = -0.1278;
        double parisLat = 48.8566, parisLon = 2.3522;
        double arc = GeoUtils.arcDistance(londonLat, londonLon, parisLat, parisLon);
        assertTrue(Math.abs(arc - 343_000) < 5_000, "arc=" + arc);
        double plane = GeoUtils.planeDistance(londonLat, londonLon, parisLat, parisLon);
        assertTrue(Math.abs(plane - arc) < 2_000);
        assertEquals(0.0, GeoUtils.arcDistance(1, 1, 1, 1), 1e-9);
    }

    @Test
    public void pointParsingFromVariousFormats() {
        GeoPoint fromMap = GeoPoint.parse(Map.of("lat", 40.0, "lon", -70.0));
        assertEquals(40.0, fromMap.lat(), 1e-9);
        assertEquals(-70.0, fromMap.lon(), 1e-9);

        GeoPoint fromString = GeoPoint.parse("40.0,-70.0");
        assertEquals(fromMap.lat(), fromString.lat(), 1e-9);
        assertEquals(fromMap.lon(), fromString.lon(), 1e-9);

        GeoPoint fromArray = GeoPoint.parse(List.of(-70.0, 40.0));
        assertEquals(fromMap.lat(), fromArray.lat(), 1e-9);
        assertEquals(fromMap.lon(), fromArray.lon(), 1e-9);

        GeoPoint fromWkt = GeoPoint.parse("POINT (-70.0 40.0)");
        assertEquals(fromMap.lat(), fromWkt.lat(), 1e-9);
        assertEquals(fromMap.lon(), fromWkt.lon(), 1e-9);

        String geohash = fromMap.geohash(8);
        GeoPoint fromGeohash = GeoPoint.parse(geohash);
        assertTrue(fromGeohash.arcDistance(fromMap) < 100);
    }

    @Test
    public void wktRoundTripForCoreShapes() {
        String[] wkts = {
            "POINT (30.0 10.0)",
            "LINESTRING (30.0 10.0, 10.0 30.0, 40.0 40.0)",
            "POLYGON ((35.0 10.0, 45.0 45.0, 15.0 40.0, 10.0 20.0, 35.0 10.0), (20.0 30.0, 35.0 35.0, 30.0 20.0, 20.0 30.0))",
            "MULTIPOINT ((10.0 40.0), (40.0 30.0))",
            "MULTIPOLYGON (((30.0 20.0, 45.0 40.0, 10.0 40.0, 30.0 20.0)), ((15.0 5.0, 40.0 10.0, 10.0 20.0, 5.0 10.0, 15.0 5.0)))"
        };
        for (String wkt : wkts) {
            Geometry g = WellKnownText.fromWKT(wkt);
            String round = WellKnownText.toWKT(g);
            Geometry g2 = WellKnownText.fromWKT(round);
            assertEquals(g, g2, "wkt=" + wkt);
        }
    }

    @Test
    public void geoJsonRoundTripForPolygon() {
        Geometry polygon = WellKnownText.fromWKT("POLYGON ((0.0 0.0, 4.0 0.0, 4.0 4.0, 0.0 4.0, 0.0 0.0))");
        Map<String, Object> map = GeoJson.toMap(polygon);
        assertEquals("Polygon", map.get("type"));
        Geometry back = GeoJson.fromMap(map);
        assertEquals(polygon, back);
    }

    @Test
    public void tessellationAreaMatchesPolygonArea() {
        Polygon square = new Polygon(new LinearRing(
            new double[] {0, 10, 10, 0, 0}, new double[] {0, 0, 10, 10, 0}));
        double polyArea = GeometryUtils.planarArea(square);
        List<Tessellator.Triangle> triangles = Tessellator.tessellate(square);
        double triArea = 0;
        for (Tessellator.Triangle t : triangles) {
            triArea += t.area();
        }
        assertEquals(polyArea, triArea, 1e-6);

        Polygon withHole = new Polygon(
            new LinearRing(new double[] {0, 10, 10, 0, 0}, new double[] {0, 0, 10, 10, 0}),
            List.of(new LinearRing(new double[] {2, 4, 4, 2, 2}, new double[] {2, 2, 4, 4, 2})));
        double holeArea = GeometryUtils.planarArea(withHole);
        double holeTriArea = 0;
        for (Tessellator.Triangle t : Tessellator.tessellate(withHole)) {
            holeTriArea += t.area();
        }
        assertEquals(holeArea, holeTriArea, 1e-6);
        assertEquals(96.0, holeArea, 1e-9);
    }

    @Test
    public void pointInPolygonAndBoundingBox() {
        Polygon square = new Polygon(new LinearRing(
            new double[] {0, 10, 10, 0, 0}, new double[] {0, 0, 10, 10, 0}));
        assertTrue(GeometryUtils.pointInPolygon(square, 5, 5));
        assertFalse(GeometryUtils.pointInPolygon(square, 15, 5));

        Rectangle bbox = GeometryUtils.boundingBox(square);
        assertEquals(0.0, bbox.getMinX(), 1e-9);
        assertEquals(10.0, bbox.getMaxX(), 1e-9);
    }

    @Test
    public void bboxDatelineCrossingAndCircleToBBox() {
        Rectangle crossing = new Rectangle(170, -170, 10, -10);
        assertTrue(crossing.crossesDateline());
        assertTrue(GeoRelations.rectangleContains(crossing, new Rectangle(175, 179, 5, -5)));
        assertFalse(GeoRelations.rectangleContains(crossing, new Rectangle(0, 5, 5, -5)));

        Rectangle circleBox = GeoUtils.circleToBBox(0, 0, 111_000);
        assertTrue(circleBox.getMinLat() < 0 && circleBox.getMaxLat() > 0);
        assertTrue(circleBox.getMinLon() < 0 && circleBox.getMaxLon() > 0);
    }

    @Test
    public void polygonRectangleRelationViaBkdStyleTest() {
        Polygon square = new Polygon(new LinearRing(
            new double[] {0, 10, 10, 0, 0}, new double[] {0, 0, 10, 10, 0}));
        assertEquals(com.naqqa.elasticsearch.common.geo.shape.Relation.CELL_INSIDE_QUERY,
            GeoRelations.relate(square, new Rectangle(2, 8, 8, 2)));
        assertEquals(com.naqqa.elasticsearch.common.geo.shape.Relation.CELL_OUTSIDE_QUERY,
            GeoRelations.relate(square, new Rectangle(20, 30, 30, 20)));
        assertEquals(com.naqqa.elasticsearch.common.geo.shape.Relation.CELL_CROSSES_QUERY,
            GeoRelations.relate(square, new Rectangle(5, 15, 15, 5)));
    }

    @Test
    public void triangleEncodingRoundTrip() {
        byte[] encoded = TriangleEncoder.encode(10, 20, true, 30, 40, false, 5, 60, true);
        TriangleEncoder.EncodedTriangle decoded = TriangleEncoder.decode(encoded);
        int minX = TriangleEncoder.minX(encoded);
        int maxX = TriangleEncoder.maxX(encoded);
        int minY = TriangleEncoder.minY(encoded);
        int maxY = TriangleEncoder.maxY(encoded);
        assertEquals(20, minX);
        assertEquals(60, maxX);
        assertEquals(5, minY);
        assertEquals(30, maxY);
        assertTrue(decoded.aY() <= decoded.bY() || decoded.aY() <= decoded.cY());
    }
}
