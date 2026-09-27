package com.naqqa.elasticsearch.codec.postings;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;

import java.io.IOException;

public abstract class PostingsEnum extends DocIdSetIterator {

    public abstract int freq() throws IOException;

    public abstract int nextPosition() throws IOException;

    public abstract int startOffset() throws IOException;

    public abstract int endOffset() throws IOException;

    public abstract byte[] getPayload() throws IOException;
}
