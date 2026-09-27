package com.naqqa.elasticsearch.search.execution;

public record TopDocs(TotalHits totalHits, ScoreDoc[] scoreDocs) {
}
