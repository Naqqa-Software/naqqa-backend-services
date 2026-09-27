package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;

import java.io.IOException;

public abstract class Query {

    public abstract Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException;

    public Query rewrite(IndexSearcher searcher) throws IOException {
        return this;
    }

    @Override
    public abstract String toString();

    @Override
    public abstract boolean equals(Object other);

    @Override
    public abstract int hashCode();
}
