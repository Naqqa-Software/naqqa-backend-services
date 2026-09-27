package com.naqqa.elasticsearch.search.advanced.pagination;

public record FieldDoc(int doc, float score, Object[] fields) {
}
