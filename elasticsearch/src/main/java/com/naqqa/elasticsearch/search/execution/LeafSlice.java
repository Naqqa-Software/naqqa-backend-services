package com.naqqa.elasticsearch.search.execution;

import java.util.List;

public final class LeafSlice {

    private final List<LeafReaderContext> leaves;

    public LeafSlice(List<LeafReaderContext> leaves) {
        this.leaves = List.copyOf(leaves);
    }

    public List<LeafReaderContext> leaves() {
        return leaves;
    }

    public int totalMaxDoc() {
        int sum = 0;
        for (LeafReaderContext ctx : leaves) {
            sum += ctx.reader().maxDoc();
        }
        return sum;
    }
}
