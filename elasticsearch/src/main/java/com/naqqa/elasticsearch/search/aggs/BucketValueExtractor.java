package com.naqqa.elasticsearch.search.aggs;

public final class BucketValueExtractor {

    private BucketValueExtractor() {
    }

    public static double extract(InternalAggregations aggs, long docCount, String path) {
        if (path == null || path.isEmpty() || "_count".equals(path)) {
            return docCount;
        }
        int dot = path.indexOf('.');
        String aggName = dot < 0 ? path : path.substring(0, dot);
        String subField = dot < 0 ? null : path.substring(dot + 1);
        InternalAggregation agg = aggs.get(aggName);
        if (agg == null) {
            throw new IllegalArgumentException("no aggregation named [" + aggName + "] found for path [" + path + "]");
        }
        if (subField == null) {
            if (agg instanceof SingleValueMetric svm) {
                return svm.value();
            }
            Object v = agg.toMap().get("value");
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            throw new IllegalArgumentException("cannot resolve numeric value for path [" + path + "]");
        }
        Object v = agg.toMap().get(subField);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw new IllegalArgumentException("cannot resolve numeric value for path [" + path + "]");
    }
}
