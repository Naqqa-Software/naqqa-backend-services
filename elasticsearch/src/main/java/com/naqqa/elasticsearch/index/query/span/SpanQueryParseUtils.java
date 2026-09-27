package com.naqqa.elasticsearch.index.query.span;

import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;
import com.naqqa.elasticsearch.index.query.QueryParser;

import java.util.Map;

final class SpanQueryParseUtils {

    private SpanQueryParseUtils() {
    }

    @SuppressWarnings("unchecked")
    static SpanQueryBuilder parseSpan(Object value, String context) {
        QueryBuilder q = QueryParser.parseQuery((Map<String, Object>) value);
        if (!(q instanceof SpanQueryBuilder span)) {
            throw QueryParseUtils.error("[{}] expected a span query but got [{}]", context, q.getWriteableName());
        }
        return span;
    }
}
