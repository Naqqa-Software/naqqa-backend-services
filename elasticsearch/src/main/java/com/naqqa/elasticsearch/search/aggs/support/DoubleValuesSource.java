package com.naqqa.elasticsearch.search.aggs.support;

import com.naqqa.elasticsearch.codec.NumericUtils;

import java.util.function.LongToDoubleFunction;

public final class DoubleValuesSource {

    private final LongValuesSource raw;
    private final LongToDoubleFunction decoder;

    private DoubleValuesSource(LongValuesSource raw, LongToDoubleFunction decoder) {
        this.raw = raw;
        this.decoder = decoder;
    }

    public static DoubleValuesSource of(LongValuesSource raw, boolean floatingPoint) {
        return new DoubleValuesSource(raw, floatingPoint ? NumericUtils::sortableLongToDouble : v -> (double) v);
    }

    public static DoubleValuesSource of(LongValuesSource raw, LongToDoubleFunction decoder) {
        return new DoubleValuesSource(raw, decoder);
    }

    public static DoubleValuesSource sortableFloat(LongValuesSource raw) {
        return new DoubleValuesSource(raw, v -> (double) NumericUtils.sortableIntToFloat((int) v));
    }

    public static DoubleValuesSource scaled(LongValuesSource raw, double scalingFactor) {
        double scale = scalingFactor <= 0 ? 1.0 : scalingFactor;
        return new DoubleValuesSource(raw, v -> v / scale);
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
        }, NumericUtils::sortableLongToDouble);
    }

    public boolean advanceExact(int doc) {
        return raw.advanceExact(doc);
    }

    public int docValueCount() {
        return raw.docValueCount();
    }

    public double nextValue() {
        return decoder.applyAsDouble(raw.nextValue());
    }
}
