package com.naqqa.elasticsearch.analysis.filter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenFilter;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class MinHashFilter extends TokenFilter {

    private static final int SHINGLE_SIZE = 5;

    private final int hashCount;
    private final int bucketCount;
    private final int hashSetSize;
    private final boolean withRotation;
    private final Deque<Token> outQueue = new ArrayDeque<>();
    private boolean built;

    public MinHashFilter(TokenStream input, int hashCount, int bucketCount, int hashSetSize, boolean withRotation) {
        super(input);
        this.hashCount = hashCount;
        this.bucketCount = bucketCount;
        this.hashSetSize = hashSetSize;
        this.withRotation = withRotation;
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
        List<String> terms = new ArrayList<>();
        while (input.incrementToken()) {
            terms.add(token.term());
        }
        List<String> shingles = new ArrayList<>();
        if (terms.size() < SHINGLE_SIZE) {
            if (!terms.isEmpty()) {
                shingles.add(String.join(" ", terms));
            }
        } else {
            for (int i = 0; i + SHINGLE_SIZE <= terms.size(); i++) {
                shingles.add(String.join(" ", terms.subList(i, i + SHINGLE_SIZE)));
            }
            if (withRotation) {
                for (int i = terms.size() - SHINGLE_SIZE + 1; i < terms.size(); i++) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < SHINGLE_SIZE; k++) {
                        if (k > 0) {
                            sb.append(' ');
                        }
                        sb.append(terms.get((i + k) % terms.size()));
                    }
                    shingles.add(sb.toString());
                }
            }
        }
        for (int hc = 0; hc < hashCount; hc++) {
            long[] minValues = new long[bucketCount];
            java.util.Arrays.fill(minValues, Long.MAX_VALUE);
            for (String shingle : shingles) {
                long h = hash(shingle, hc);
                int bucket = (int) Math.floorMod(h, bucketCount);
                if (h < minValues[bucket]) {
                    minValues[bucket] = h;
                }
            }
            int emitted = 0;
            for (int b = 0; b < bucketCount && emitted < hashSetSize; b++) {
                if (minValues[b] == Long.MAX_VALUE) {
                    continue;
                }
                Token t = new Token();
                t.setTerm(Long.toString(minValues[b]));
                t.setOffset(0, 0);
                t.setPositionIncrement(outQueue.isEmpty() && emitted == 0 ? 1 : 0);
                t.setType("min_hash");
                outQueue.add(t);
                emitted++;
            }
        }
    }

    private static long hash(String s, int seed) {
        long h = 1125899906842597L + seed * 0x9E3779B97F4A7C15L;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }
}
