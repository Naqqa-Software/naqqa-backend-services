package com.naqqa.elasticsearch.search.bridge.geo;

import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.codec.points.IntersectVisitor;
import com.naqqa.elasticsearch.codec.points.Relation;
import com.naqqa.elasticsearch.common.geo.GeoEncodingUtils;
import com.naqqa.elasticsearch.common.geo.TriangleEncoder;
import com.naqqa.elasticsearch.common.geo.format.WellKnownText;
import com.naqqa.elasticsearch.common.geo.geometry.Geometry;
import com.naqqa.elasticsearch.common.geo.geometry.Point;
import com.naqqa.elasticsearch.common.util.FixedBitSet;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class GeoShapeQuery extends Query {

    private final String field;
    private final Geometry queryShape;
    private final GeoShapeRelations.Kind relation;

    public GeoShapeQuery(String field, Geometry queryShape, GeoShapeRelations.Kind relation) {
        this.field = Objects.requireNonNull(field);
        this.queryShape = Objects.requireNonNull(queryShape);
        this.relation = relation;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new GeoShapeWeight(this, boost);
    }

    @Override
    public String toString() {
        return "GeoShapeQuery(" + field + ", " + relation + ", " + queryShape + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GeoShapeQuery q && field.equals(q.field) && queryShape.equals(q.queryShape) && relation == q.relation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, queryShape, relation);
    }

    private boolean triangleMatches(byte[] packedValue) {
        TriangleEncoder.EncodedTriangle t = TriangleEncoder.decode(packedValue);
        double aLat = GeoEncodingUtils.decodeLatitude(t.aY());
        double aLon = GeoEncodingUtils.decodeLongitude(t.aX());
        double bLat = GeoEncodingUtils.decodeLatitude(t.bY());
        double bLon = GeoEncodingUtils.decodeLongitude(t.bX());
        double cLat = GeoEncodingUtils.decodeLatitude(t.cY());
        double cLon = GeoEncodingUtils.decodeLongitude(t.cX());
        Point pa = new Point(aLon, aLat);
        Point pb = new Point(bLon, bLat);
        Point pc = new Point(cLon, cLat);
        return switch (relation) {
            case INTERSECTS -> GeoShapeRelations.test(pa, queryShape, GeoShapeRelations.Kind.INTERSECTS)
                || GeoShapeRelations.test(pb, queryShape, GeoShapeRelations.Kind.INTERSECTS)
                || GeoShapeRelations.test(pc, queryShape, GeoShapeRelations.Kind.INTERSECTS);
            case DISJOINT -> GeoShapeRelations.test(pa, queryShape, GeoShapeRelations.Kind.DISJOINT)
                && GeoShapeRelations.test(pb, queryShape, GeoShapeRelations.Kind.DISJOINT)
                && GeoShapeRelations.test(pc, queryShape, GeoShapeRelations.Kind.DISJOINT);
            case WITHIN -> GeoShapeRelations.test(pa, queryShape, GeoShapeRelations.Kind.WITHIN)
                && GeoShapeRelations.test(pb, queryShape, GeoShapeRelations.Kind.WITHIN)
                && GeoShapeRelations.test(pc, queryShape, GeoShapeRelations.Kind.WITHIN);
            case CONTAINS -> GeoShapeRelations.test(pa, queryShape, GeoShapeRelations.Kind.CONTAINS)
                || GeoShapeRelations.test(pb, queryShape, GeoShapeRelations.Kind.CONTAINS)
                || GeoShapeRelations.test(pc, queryShape, GeoShapeRelations.Kind.CONTAINS);
        };
    }

    private FixedBitSet buildBits(LeafReaderContext context) throws IOException {
        int maxDoc = context.reader().maxDoc();
        FixedBitSet bits = new FixedBitSet(maxDoc);
        boolean[] any = {false};
        BKDReader bkd = context.reader().pointValues(field);
        if (bkd != null && bkd.numDims() == TriangleEncoder.NUM_DIMS && bkd.bytesPerDim() == TriangleEncoder.BYTES_PER_DIM) {
            bkd.intersect(new IntersectVisitor() {
                @Override
                public Relation compare(byte[][] cellMin, byte[][] cellMax) {
                    return Relation.CELL_CROSSES_QUERY;
                }

                @Override
                public void visit(int docId) {
                    bits.set(docId);
                    any[0] = true;
                }

                @Override
                public void visit(int docId, byte[] packedValue) {
                    if (triangleMatches(packedValue)) {
                        bits.set(docId);
                        any[0] = true;
                    }
                }
            });
        } else {
            var dv = context.reader().sortedDocValues(field);
            if (dv != null) {
                for (int d = 0; d < maxDoc; d++) {
                    if (dv.advanceExact(d)) {
                        byte[] wkt = dv.lookupOrd(dv.ordValue());
                        Geometry docGeometry = WellKnownText.fromWKT(new String(wkt, StandardCharsets.UTF_8));
                        if (GeoShapeRelations.test(docGeometry, queryShape, relation)) {
                            bits.set(d);
                            any[0] = true;
                        }
                    }
                }
            }
        }
        return any[0] ? bits : null;
    }

    private final class GeoShapeWeight extends Weight {
        private final float boost;

        GeoShapeWeight(Query query, float boost) {
            super(query);
            this.boost = boost;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            FixedBitSet bits = buildBits(context);
            return bits == null ? null : new GeoBitSetScorer(this, bits, boost);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            FixedBitSet bits = buildBits(context);
            if (bits != null && doc < bits.length() && bits.get(doc)) {
                return Explanation.match(boost, "doc " + doc + " matches geo_shape relation " + relation + " on field [" + field + "]");
            }
            return Explanation.noMatch("doc " + doc + " does not match geo_shape relation " + relation + " on field [" + field + "]");
        }
    }
}
