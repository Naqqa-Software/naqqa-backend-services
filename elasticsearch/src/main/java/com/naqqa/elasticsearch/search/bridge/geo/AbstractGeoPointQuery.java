package com.naqqa.elasticsearch.search.bridge.geo;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.codec.points.IntersectVisitor;
import com.naqqa.elasticsearch.codec.points.Relation;
import com.naqqa.elasticsearch.common.util.FixedBitSet;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;

public abstract class AbstractGeoPointQuery extends Query {

    protected final String field;

    protected AbstractGeoPointQuery(String field) {
        this.field = field;
    }

    public String field() {
        return field;
    }

    protected abstract boolean matches(double lat, double lon);

    protected abstract Relation compareCell(double cellMinLat, double cellMaxLat, double cellMinLon, double cellMaxLon);

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        return new GeoWeight(this, boost);
    }

    private FixedBitSet buildBits(LeafReaderContext context) throws IOException {
        int maxDoc = context.reader().maxDoc();
        FixedBitSet bits = new FixedBitSet(maxDoc);
        boolean[] any = {false};
        BKDReader bkd = context.reader().pointValues(field);
        if (bkd != null && bkd.numDims() == 2 && bkd.bytesPerDim() == 8) {
            bkd.intersect(new IntersectVisitor() {
                @Override
                public Relation compare(byte[][] cellMin, byte[][] cellMax) {
                    double minLat = NumericUtils.sortableLongToDouble(NumericUtils.sortableBytesToLong(cellMin[0], 0));
                    double maxLat = NumericUtils.sortableLongToDouble(NumericUtils.sortableBytesToLong(cellMax[0], 0));
                    double minLon = NumericUtils.sortableLongToDouble(NumericUtils.sortableBytesToLong(cellMin[1], 0));
                    double maxLon = NumericUtils.sortableLongToDouble(NumericUtils.sortableBytesToLong(cellMax[1], 0));
                    return compareCell(minLat, maxLat, minLon, maxLon);
                }

                @Override
                public void visit(int docId) {
                    bits.set(docId);
                    any[0] = true;
                }

                @Override
                public void visit(int docId, byte[] packedValue) {
                    double lat = NumericUtils.sortableLongToDouble(NumericUtils.sortableBytesToLong(packedValue, 0));
                    double lon = NumericUtils.sortableLongToDouble(NumericUtils.sortableBytesToLong(packedValue, 8));
                    if (matches(lat, lon)) {
                        bits.set(docId);
                        any[0] = true;
                    }
                }
            });
        } else {
            var dv = context.reader().numericDocValues(field);
            if (dv != null) {
                for (int d = 0; d < maxDoc; d++) {
                    if (dv.advanceExact(d)) {
                        long encoded = dv.longValue();
                        double lat = GeoFieldAccess.decodeLat(encoded);
                        double lon = GeoFieldAccess.decodeLon(encoded);
                        if (matches(lat, lon)) {
                            bits.set(d);
                            any[0] = true;
                        }
                    }
                }
            }
        }
        return any[0] ? bits : null;
    }

    private final class GeoWeight extends Weight {
        private final float boost;

        GeoWeight(Query query, float boost) {
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
                return Explanation.match(boost, "doc " + doc + " matches geo predicate on field [" + field + "]");
            }
            return Explanation.noMatch("doc " + doc + " does not match geo predicate on field [" + field + "]");
        }
    }
}
