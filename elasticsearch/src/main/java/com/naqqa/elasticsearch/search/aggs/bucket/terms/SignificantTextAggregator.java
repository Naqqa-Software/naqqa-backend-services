package com.naqqa.elasticsearch.search.aggs.bucket.terms;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;

public final class SignificantTextAggregator {

    private SignificantTextAggregator() {
    }

    public static Aggregator parse(AggParseContext ctx) {
        return SignificantTermsAggregator.parse(ctx);
    }
}
