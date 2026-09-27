package com.naqqa.elasticsearch.search.execution;

public final class LeafReaderContext {

    private final LeafReader reader;
    private final int docBase;
    private final int ord;

    public LeafReaderContext(LeafReader reader, int docBase, int ord) {
        this.reader = reader;
        this.docBase = docBase;
        this.ord = ord;
    }

    public LeafReader reader() {
        return reader;
    }

    public int docBase() {
        return docBase;
    }

    public int ord() {
        return ord;
    }
}
