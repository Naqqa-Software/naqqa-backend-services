package com.naqqa.elasticsearch.search.execution;

public record TotalHits(long value, Relation relation) {

    public enum Relation { EQUAL_TO, GREATER_THAN_OR_EQUAL_TO }
}
