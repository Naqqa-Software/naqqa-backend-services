package com.naqqa.elasticsearch.codec.termvectors;

public record TermVectorTerm(byte[] term, int freq, int[] positions, int[] startOffsets, int[] endOffsets) {
}
