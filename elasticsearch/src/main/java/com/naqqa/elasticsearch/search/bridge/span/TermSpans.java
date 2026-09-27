package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.codec.postings.PostingsEnum;

import java.io.IOException;

final class TermSpans extends Spans {

    private final PostingsEnum postings;
    private int start = -1;
    private int end = -1;
    private int count;
    private int read;

    TermSpans(PostingsEnum postings) {
        this.postings = postings;
    }

    @Override
    public int docID() {
        return postings.docID();
    }

    @Override
    public int nextDoc() throws IOException {
        int doc = postings.nextDoc();
        reset(doc);
        return doc;
    }

    @Override
    public int advance(int target) throws IOException {
        int doc = postings.advance(target);
        reset(doc);
        return doc;
    }

    private void reset(int doc) throws IOException {
        start = -1;
        end = -1;
        read = 0;
        count = doc == NO_MORE_DOCS ? 0 : postings.freq();
    }

    @Override
    public long cost() {
        return postings.cost();
    }

    @Override
    public int nextStartPosition() throws IOException {
        if (read >= count) {
            start = NO_MORE_POSITIONS;
            end = NO_MORE_POSITIONS;
            return NO_MORE_POSITIONS;
        }
        start = postings.nextPosition();
        end = start + 1;
        read++;
        return start;
    }

    @Override
    public int startPosition() {
        return start;
    }

    @Override
    public int endPosition() {
        return end;
    }

    @Override
    public int width() {
        return 1;
    }
}
