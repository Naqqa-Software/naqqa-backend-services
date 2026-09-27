package com.naqqa.elasticsearch.search.bridge.geo;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.codec.points.BKDWriter;
import com.naqqa.elasticsearch.common.geo.GeoDistance;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class GeoQueriesTest {

    private static final double[] LAT = {40.7128, 34.0522, 51.5074, 48.8566, 35.6895};
    private static final double[] LON = {-74.0060, -118.2437, -0.1278, 2.3522, 139.6917};
    private static final int MAX_DOC = LAT.length;

    private static IndexSearcher buildFallbackSearcher() throws Exception {
        long[] encoded = new long[MAX_DOC];
        for (int i = 0; i < MAX_DOC; i++) {
            encoded[i] = GeoFieldAccess.encode(LAT[i], LON[i]);
        }
        var dv = com.naqqa.elasticsearch.search.execution.TestSegments.buildNumericDocValues(MAX_DOC, encoded, null);
        SimpleLeafReader reader = SimpleLeafReader.builder(MAX_DOC)
            .numericDocValues("location", dv)
            .build();
        return new IndexSearcher(List.of(reader));
    }

    private static BKDReader buildPointReader() throws Exception {
        BKDWriter writer = new BKDWriter(2, 8, 512);
        for (int i = 0; i < MAX_DOC; i++) {
            byte[] packed = new byte[16];
            NumericUtils.longToSortableBytes(NumericUtils.doubleToSortableLong(LAT[i]), packed, 0);
            NumericUtils.longToSortableBytes(NumericUtils.doubleToSortableLong(LON[i]), packed, 8);
            writer.add(packed, i);
        }
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        try (IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT)) {
            writer.finish(out);
        }
        IndexInput in = dir.openInput("bkd", IOContext.DEFAULT);
        return new BKDReader(in);
    }

    private static IndexSearcher buildBkdSearcher() throws Exception {
        BKDReader points = buildPointReader();
        SimpleLeafReader reader = SimpleLeafReader.builder(MAX_DOC)
            .points("location", points)
            .build();
        return new IndexSearcher(List.of(reader));
    }

    private static Set<Integer> docIds(TopDocs topDocs) {
        Set<Integer> ids = new TreeSet<>();
        for (ScoreDoc sd : topDocs.scoreDocs()) {
            ids.add(sd.doc);
        }
        return ids;
    }

    @Test
    public void boundingBoxFallbackMatchesPointsWithinBox() throws Exception {
        IndexSearcher searcher = buildFallbackSearcher();
        GeoBoundingBoxQuery query = new GeoBoundingBoxQuery("location", 25.0, 49.0, -125.0, -65.0);
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(0, 1), docIds(topDocs));
    }

    @Test
    public void boundingBoxBkdPathMatchesPointsWithinBox() throws Exception {
        IndexSearcher searcher = buildBkdSearcher();
        GeoBoundingBoxQuery query = new GeoBoundingBoxQuery("location", 25.0, 49.0, -125.0, -65.0);
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(0, 1), docIds(topDocs));
    }

    @Test
    public void distanceFallbackMatchesPointsWithinRadius() throws Exception {
        IndexSearcher searcher = buildFallbackSearcher();
        GeoDistanceQuery query = new GeoDistanceQuery("location", 48.8566, 2.3522, 400_000, GeoDistance.ARC);
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(2, 3), docIds(topDocs));
    }

    @Test
    public void distanceFallbackExcludesFarPoints() throws Exception {
        IndexSearcher searcher = buildFallbackSearcher();
        GeoDistanceQuery query = new GeoDistanceQuery("location", 40.7128, -74.0060, 100_000, GeoDistance.ARC);
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(0), docIds(topDocs));
    }

    @Test
    public void polygonQueryMatchesPointsInsidePolygon() throws Exception {
        IndexSearcher searcher = buildFallbackSearcher();
        List<com.naqqa.elasticsearch.common.geo.GeoPoint> points = List.of(
            new com.naqqa.elasticsearch.common.geo.GeoPoint(60.0, -10.0),
            new com.naqqa.elasticsearch.common.geo.GeoPoint(60.0, 10.0),
            new com.naqqa.elasticsearch.common.geo.GeoPoint(40.0, 10.0),
            new com.naqqa.elasticsearch.common.geo.GeoPoint(40.0, -10.0),
            new com.naqqa.elasticsearch.common.geo.GeoPoint(60.0, -10.0));
        GeoPolygonQuery query = new GeoPolygonQuery("location", points);
        TopDocs topDocs = searcher.search(query, 10);
        assertEquals(Set.of(2, 3), docIds(topDocs));
    }
}
