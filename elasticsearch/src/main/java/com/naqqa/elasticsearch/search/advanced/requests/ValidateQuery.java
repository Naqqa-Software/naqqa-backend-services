package com.naqqa.elasticsearch.search.advanced.requests;

import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParser;
import com.naqqa.elasticsearch.search.advanced.common.QueryBuilderToQuery;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.Map;

public final class ValidateQuery {

    private ValidateQuery() {
    }

    public record Result(boolean valid, String explanation, String error) {

        public static Result valid(String explanation) {
            return new Result(true, explanation, null);
        }

        public static Result invalid(String error) {
            return new Result(false, null, error);
        }
    }

    public static Result validate(Map<String, Object> queryJson, IndexSearcher searcher) {
        QueryBuilder builder;
        try {
            builder = QueryParser.parseQuery(queryJson);
        } catch (RuntimeException e) {
            return Result.invalid(e.getMessage());
        }
        String explanation = builder.toMap().toString();
        if (searcher != null) {
            try {
                Query query = QueryBuilderToQuery.convert(builder);
                Query rewritten = searcher.rewrite(query);
                explanation = rewritten.toString();
            } catch (IllegalArgumentException unsupportedConversion) {
                // The parsed query type isn't covered by the executable-query converter yet; parse-level
                // validation above already succeeded, so we still report the query as valid.
            } catch (IOException | RuntimeException e) {
                return Result.invalid(e.getMessage());
            }
        }
        return Result.valid(explanation);
    }
}
