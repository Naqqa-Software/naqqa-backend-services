package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;

import java.io.IOException;

public abstract class Spans extends DocIdSetIterator {

    public static final int NO_MORE_POSITIONS = Integer.MAX_VALUE;

    public abstract int nextStartPosition() throws IOException;

    public abstract int startPosition();

    public abstract int endPosition();

    public abstract int width();
}
