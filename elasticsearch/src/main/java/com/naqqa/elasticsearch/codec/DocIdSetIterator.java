package com.naqqa.elasticsearch.codec;

import java.io.IOException;

public abstract class DocIdSetIterator {

    public static final int NO_MORE_DOCS = Integer.MAX_VALUE;

    public abstract int docID();

    public abstract int nextDoc() throws IOException;

    public abstract int advance(int target) throws IOException;

    public abstract long cost();

    public int slowAdvance(int target) throws IOException {
        int doc;
        do {
            doc = nextDoc();
        } while (doc < target);
        return doc;
    }

    public static DocIdSetIterator empty() {
        return new DocIdSetIterator() {
            private int doc = -1;

            @Override
            public int docID() {
                return doc;
            }

            @Override
            public int nextDoc() {
                return doc = NO_MORE_DOCS;
            }

            @Override
            public int advance(int target) {
                return doc = NO_MORE_DOCS;
            }

            @Override
            public long cost() {
                return 0;
            }
        };
    }
}
