package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.TreeMap;

public final class FlattenGraphFilter extends TokenFilter {

    private static final class Buffered {
        Token token;
        int startNode;
        int endNode;
    }

    private final Deque<Token> outQueue = new ArrayDeque<>();
    private boolean built;

    public FlattenGraphFilter(TokenStream input) {
        super(input);
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
        List<Buffered> items = new ArrayList<>();
        int node = 0;
        while (input.incrementToken()) {
            Buffered b = new Buffered();
            b.token = token.copy();
            node += b.token.positionIncrement();
            b.startNode = node;
            b.endNode = node + b.token.positionLength();
            items.add(b);
        }
        if (items.isEmpty()) {
            return;
        }
        TreeMap<Integer, Integer> outputPos = new TreeMap<>();
        outputPos.put(items.get(0).startNode, 0);
        for (Buffered b : items) {
            outputPos.putIfAbsent(b.startNode, null);
            outputPos.putIfAbsent(b.endNode, null);
        }
        for (Integer key : outputPos.keySet()) {
            if (outputPos.get(key) == null) {
                outputPos.put(key, 0);
            }
        }
        for (int pass = 0; pass < items.size() + 1; pass++) {
            boolean changed = false;
            for (Buffered b : items) {
                int candidate = outputPos.get(b.startNode) + 1;
                int current = outputPos.get(b.endNode);
                if (candidate > current) {
                    outputPos.put(b.endNode, candidate);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        int cursor = 0;
        int prevStart = -1;
        for (Buffered b : items) {
            int outStart = outputPos.get(b.startNode);
            int outEnd = outputPos.get(b.endNode);
            int posInc;
            if (b.startNode != prevStart) {
                posInc = outStart - cursor;
                cursor = outStart;
                prevStart = b.startNode;
            } else {
                posInc = 0;
            }
            Token t = b.token;
            t.setPositionIncrement(Math.max(0, posInc));
            t.setPositionLength(Math.max(1, outEnd - outStart));
            outQueue.add(t);
        }
    }
}
