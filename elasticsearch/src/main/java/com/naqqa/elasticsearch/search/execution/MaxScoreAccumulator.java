package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.codec.NumericUtils;

import java.util.concurrent.atomic.LongAccumulator;

public final class MaxScoreAccumulator {

    private static final long NO_VALUE = Long.MIN_VALUE;

    private final LongAccumulator acc = new LongAccumulator(MaxScoreAccumulator::maxByScoreThenDoc, NO_VALUE);

    private static long encode(int doc, float score) {
        long sortableScore = NumericUtils.floatToSortableInt(score) & 0xFFFFFFFFL;
        return (sortableScore << 32) | (doc & 0xFFFFFFFFL);
    }

    private static float decodeScore(long encoded) {
        return NumericUtils.sortableIntToFloat((int) (encoded >>> 32));
    }

    private static int decodeDoc(long encoded) {
        return (int) encoded;
    }

    private static long maxByScoreThenDoc(long v1, long v2) {
        if (v1 == NO_VALUE) {
            return v2;
        }
        if (v2 == NO_VALUE) {
            return v1;
        }
        int cmp = Float.compare(decodeScore(v1), decodeScore(v2));
        if (cmp == 0) {
            cmp = Integer.compare(decodeDoc(v1), decodeDoc(v2));
        }
        return cmp >= 0 ? v1 : v2;
    }

    public void accumulate(int doc, float score) {
        acc.accumulate(encode(doc, score));
    }

    public float rawMaxScore() {
        long v = acc.get();
        return v == NO_VALUE ? Float.NEGATIVE_INFINITY : decodeScore(v);
    }
}
