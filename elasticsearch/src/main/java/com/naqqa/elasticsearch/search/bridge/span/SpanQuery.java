package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Weight;

import java.io.IOException;

public abstract class SpanQuery extends Query {

    public abstract String field();

    public abstract SpanWeight createSpanWeight(IndexSearcher searcher, float boost) throws IOException;

    @Override
    public final Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        return createSpanWeight(searcher, boost);
    }
}
