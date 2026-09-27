package com.naqqa.elasticsearch.index.mapper;

public record IndexedTerm(String term, int position, int startOffset, int endOffset) {
}
