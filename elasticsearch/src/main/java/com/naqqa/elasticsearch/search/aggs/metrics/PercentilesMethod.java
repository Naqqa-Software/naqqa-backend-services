package com.naqqa.elasticsearch.search.aggs.metrics;

import java.util.Locale;

public enum PercentilesMethod {
    TDIGEST,
    HDR;

    public static PercentilesMethod fromString(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "hdr" -> HDR;
            case "tdigest" -> TDIGEST;
            default -> throw new IllegalArgumentException("unknown percentiles method [" + s + "]");
        };
    }
}
