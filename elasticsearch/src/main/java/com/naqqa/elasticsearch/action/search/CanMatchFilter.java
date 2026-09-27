package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;

import java.io.IOException;

public interface CanMatchFilter {

    boolean mightMatch(IndexSearcher searcher, String field, long lower, long upper) throws IOException;

    static CanMatchFilter longRange() {
        return new LongRangeCanMatchFilter();
    }

    final class LongRangeCanMatchFilter implements CanMatchFilter {

        @Override
        public boolean mightMatch(IndexSearcher searcher, String field, long lower, long upper) throws IOException {
            boolean anyData = false;
            for (LeafReaderContext ctx : searcher.leafContexts()) {
                long[] minMax = leafMinMax(ctx, field);
                if (minMax == null) {
                    continue;
                }
                anyData = true;
                if (!(minMax[1] < lower || minMax[0] > upper)) {
                    return true;
                }
            }
            return !anyData;
        }

        private long[] leafMinMax(LeafReaderContext ctx, String field) throws IOException {
            BKDReader points = ctx.reader().pointValues(field);
            if (points != null && points.numPoints() > 0) {
                long min = NumericUtils.sortableBytesToLong(points.minPackedValue(0), 0);
                long max = NumericUtils.sortableBytesToLong(points.maxPackedValue(0), 0);
                return new long[] {min, max};
            }
            NumericDocValuesReader dv = ctx.reader().numericDocValues(field);
            if (dv == null) {
                return null;
            }
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            boolean any = false;
            for (int doc = 0; doc < ctx.reader().maxDoc(); doc++) {
                if (!ctx.reader().isLive(doc)) {
                    continue;
                }
                if (dv.advanceExact(doc)) {
                    long v = dv.longValue();
                    if (v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                    any = true;
                }
            }
            return any ? new long[] {min, max} : null;
        }
    }
}
