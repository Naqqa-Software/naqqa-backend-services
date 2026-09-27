package com.naqqa.elasticsearch.index.query;

import java.util.Map;

public interface QueryBuilder {

    String getWriteableName();

    Map<String, Object> toMap();

    default <R> R accept(QueryVisitor<R> visitor) {
        return visitor.visit(this);
    }
}
