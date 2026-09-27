package com.naqqa.elasticsearch.index.query;

public interface QueryVisitor<R> {

    R visit(QueryBuilder query);
}
