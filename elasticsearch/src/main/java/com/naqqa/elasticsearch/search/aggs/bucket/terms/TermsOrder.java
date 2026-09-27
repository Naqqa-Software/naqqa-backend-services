package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.search.aggs.BucketValueExtractor;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.Comparator;
import java.util.Map;

public final class TermsOrder {

    public final String path;
    public final boolean ascending;

    public TermsOrder(String path, boolean ascending) {
        this.path = path;
        this.ascending = ascending;
    }

    public static TermsOrder count(boolean ascending) {
        return new TermsOrder("_count", ascending);
    }

    @SuppressWarnings("unchecked")
    public static TermsOrder parse(Object orderSpec) {
        if (orderSpec == null) {
            return count(false);
        }
        Map<String, Object> map = ParamsHelper.asMap(orderSpec);
        if (map.isEmpty()) {
            return count(false);
        }
        Map.Entry<String, Object> entry = map.entrySet().iterator().next();
        boolean ascending = "asc".equals(String.valueOf(entry.getValue()));
        return new TermsOrder(entry.getKey(), ascending);
    }

    public Comparator<TermsBucket> comparator() {
        Comparator<TermsBucket> cmp;
        if ("_key".equals(path)) {
            cmp = (a, b) -> compareKeys(a.getKey(), b.getKey());
        } else {
            cmp = Comparator.comparingDouble(b -> BucketValueExtractor.extract((InternalAggregations) b.getAggregations(), b.getDocCount(), path));
        }
        return ascending ? cmp : cmp.reversed();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeys(Object a, Object b) {
        if (a instanceof Comparable && b instanceof Comparable) {
            return ((Comparable) a).compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }
}
