package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;

import java.io.IOException;
import java.util.Objects;

public final class BoostQuery extends Query {

    private final Query inner;
    private final float boost;

    public BoostQuery(Query inner, float boost) {
        this.inner = inner;
        this.boost = boost;
    }

    public Query inner() {
        return inner;
    }

    public float boost() {
        return boost;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rewritten = inner.rewrite(searcher);
        if (rewritten instanceof BoostQuery bq) {
            return new BoostQuery(bq.inner, boost * bq.boost);
        }
        if (rewritten != inner) {
            return new BoostQuery(rewritten, boost);
        }
        return this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        return inner.createWeight(searcher, scoreMode, boost * this.boost);
    }

    @Override
    public String toString() {
        return inner + "^" + boost;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BoostQuery bq && boost == bq.boost && inner.equals(bq.inner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(BoostQuery.class, inner, boost);
    }
}
