package com.naqqa.elasticsearch.search.execution;

public record TotalHits(long value, Relation relation) {

    public enum Relation { EQUAL_TO, GREATER_THAN_OR_EQUAL_TO }

    public static final long TRACK_TOTAL_HITS_ACCURATE = Long.MAX_VALUE;
    public static final long TRACK_TOTAL_HITS_DISABLED = -1L;
    public static final long DEFAULT_TRACK_TOTAL_HITS_UP_TO = 10_000L;

    public static int collectorThreshold(long trackTotalHitsUpTo) {
        if (trackTotalHitsUpTo < 0) {
            return 0;
        }
        return trackTotalHitsUpTo >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) trackTotalHitsUpTo;
    }

    public static Relation relationFor(long rawValue, long trackTotalHitsUpTo) {
        if (trackTotalHitsUpTo < 0 || trackTotalHitsUpTo == TRACK_TOTAL_HITS_ACCURATE) {
            return trackTotalHitsUpTo == TRACK_TOTAL_HITS_ACCURATE ? Relation.EQUAL_TO : Relation.GREATER_THAN_OR_EQUAL_TO;
        }
        return rawValue > trackTotalHitsUpTo ? Relation.GREATER_THAN_OR_EQUAL_TO : Relation.EQUAL_TO;
    }

    public static TotalHits merge(long sumValue, boolean anyGreaterOrEqual, long trackTotalHitsUpTo) {
        if (trackTotalHitsUpTo == TRACK_TOTAL_HITS_ACCURATE) {
            return new TotalHits(sumValue, anyGreaterOrEqual ? Relation.GREATER_THAN_OR_EQUAL_TO : Relation.EQUAL_TO);
        }
        boolean gte = anyGreaterOrEqual || sumValue > trackTotalHitsUpTo;
        long value = gte ? Math.min(sumValue, trackTotalHitsUpTo) : sumValue;
        return new TotalHits(value, gte ? Relation.GREATER_THAN_OR_EQUAL_TO : Relation.EQUAL_TO);
    }
}
