package com.naqqa.elasticsearch.search.execution;

public record FieldTopDocs(TotalHits totalHits, FieldDoc[] fieldDocs) {
}
