package com.naqqa.elasticsearch.search.aggs.support;

import com.naqqa.elasticsearch.codec.NumericUtils;

public final class DoubleValuesSource {

    private final LongValuesSource raw;
    private final boolean sortableDouble;

    private DoubleValuesSource(LongValuesSource raw, boolean sortableDouble) {
        this.raw = raw;
        this.sortableDouble = sortableDouble;
    }

    public static DoubleValuesSource of(LongValuesSource raw, boolean floatingPoint) {
        return new DoubleValuesSource(raw, floatingPoint);
    }

    public static DoubleValuesSource constant(double value, java.util.function.IntPredicate hasDoc) {
        return new DoubleValuesSource(new LongValuesSource() {
            private boolean present;

            @Override
            public boolean advanceExact(int doc) {
                present = hasDoc.test(doc);
                return present;
            }

            @Override
            public int docValueCount() {
                return present ? 1 : 0;
            }

            @Override
            public long nextValue() {
                return NumericUtils.doubleToSortableLong(value);
            }
        }, true);
    }

    public boolean advanceExact(int doc) {
        return raw.advanceExact(doc);
    }

    public int docValueCount() {
        return raw.docValueCount();
    }

    public double nextValue() {
        long v = raw.nextValue();
        return sortableDouble ? NumericUtils.sortableLongToDouble(v) : (double) v;
    }
}
