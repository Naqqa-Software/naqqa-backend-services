package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ShingleFilter extends TokenFilter {

    private final int minShingleSize;
    private final int maxShingleSize;
    private final boolean outputUnigrams;
    private final boolean outputUnigramsIfNoShingles;
    private final String tokenSeparator;
    private final String fillerToken;

    private final Deque<Token> outQueue = new ArrayDeque<>();
    private boolean built;

    public ShingleFilter(TokenStream input, int minShingleSize, int maxShingleSize, boolean outputUnigrams,
                          boolean outputUnigramsIfNoShingles, String tokenSeparator, String fillerToken) {
        super(input);
        this.minShingleSize = minShingleSize;
        this.maxShingleSize = maxShingleSize;
        this.outputUnigrams = outputUnigrams;
        this.outputUnigramsIfNoShingles = outputUnigramsIfNoShingles;
        this.tokenSeparator = tokenSeparator == null ? " " : tokenSeparator;
        this.fillerToken = fillerToken == null ? "_" : fillerToken;
    }

    @Override
    public void reset() {
        super.reset();
        outQueue.clear();
        built = false;
    }

    @Override
    public boolean incrementToken() {
        if (!built) {
            build();
            built = true;
        }
        if (outQueue.isEmpty()) {
            return false;
        }
        token.clear();
        token.copyFrom(outQueue.poll());
        return true;
    }

    private void build() {
        List<Token> buf = new ArrayList<>();
        List<Integer> absPos = new ArrayList<>();
        int pos = -1;
        while (input.incrementToken()) {
            pos += token.positionIncrement();
            buf.add(token.copy());
            absPos.add(pos);
        }
        int n = buf.size();
        boolean anyShingle = n >= Math.max(2, minShingleSize);
        boolean emitUnigrams = outputUnigrams || (!anyShingle && outputUnigramsIfNoShingles);
        int lastEmitted = -1;
        List<int[]> emitOrder = new ArrayList<>();
        for (int start = 0; start < n; start++) {
            if (emitUnigrams) {
                emitOrder.add(new int[]{start, 1});
            }
            int maxSize = Math.min(maxShingleSize, n - start);
            for (int size = Math.max(2, minShingleSize); size <= maxSize; size++) {
                emitOrder.add(new int[]{start, size});
            }
        }
        emitOrder.sort((a, b) -> {
            int cmp = Integer.compare(absPos.get(a[0]), absPos.get(b[0]));
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(a[1], b[1]);
        });
        for (int[] e : emitOrder) {
            int start = e[0];
            int size = e[1];
            int end = start + size - 1;
            StringBuilder sb = new StringBuilder();
            int expectedPos = absPos.get(start);
            for (int i = start; i <= end; i++) {
                if (i > start) {
                    sb.append(tokenSeparator);
                    int gap = absPos.get(i) - absPos.get(i - 1) - 1;
                    for (int g = 0; g < gap; g++) {
                        sb.append(fillerToken).append(tokenSeparator);
                    }
                }
                sb.append(buf.get(i).term());
            }
            Token t = new Token();
            t.setTerm(sb);
            t.setOffset(buf.get(start).startOffset(), buf.get(end).endOffset());
            t.setPositionLength(size);
            int abs = absPos.get(start);
            t.setPositionIncrement(Math.max(0, abs - lastEmitted));
            t.setType(size == 1 ? "word" : "shingle");
            lastEmitted = abs;
            outQueue.add(t);
        }
    }
}
