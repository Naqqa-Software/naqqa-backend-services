package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParser;
import com.naqqa.elasticsearch.search.advanced.common.QueryBuilderToQuery;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.search.query.Query;

import java.util.Map;

final class QueryClauseConverter {

    private QueryClauseConverter() {
    }

    static Query convert(Map<String, Object> queryClause) {
        if (queryClause == null || queryClause.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        QueryBuilder builder = QueryParser.parseQuery(queryClause);
        return QueryBuilderToQuery.convert(builder);
    }
}
