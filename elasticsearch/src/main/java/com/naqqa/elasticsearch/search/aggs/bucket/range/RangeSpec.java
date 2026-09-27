package com.naqqa.elasticsearch.search.aggs.bucket.range;

public record RangeSpec(String key, Double from, Double to) {

    public boolean matches(double value) {
        if (from != null && value < from) {
            return false;
        }
        return to == null || value < to;
    }

    public String effectiveKey() {
        if (key != null) {
            return key;
        }
        String fromStr = from == null ? "*" : String.valueOf(from);
        String toStr = to == null ? "*" : String.valueOf(to);
        return fromStr + "-" + toStr;
    }
}
