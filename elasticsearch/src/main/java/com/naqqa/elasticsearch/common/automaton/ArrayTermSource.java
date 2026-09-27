package com.naqqa.elasticsearch.common.automaton;

import java.util.Arrays;

public final class ArrayTermSource implements SortedTermSource {

    private final byte[][] terms;
    private int pos = -1;
    private int seekCount;
    private int nextCount;

    public ArrayTermSource(byte[][] sortedTerms) {
        this.terms = sortedTerms;
    }

    public static ArrayTermSource ofUnsorted(byte[][] terms) {
        byte[][] copy = terms.clone();
        Arrays.sort(copy, Arrays::compareUnsigned);
        int n = 0;
        for (int i = 0; i < copy.length; i++) {
            if (n == 0 || !Arrays.equals(copy[n - 1], copy[i])) {
                copy[n++] = copy[i];
            }
        }
        return new ArrayTermSource(Arrays.copyOf(copy, n));
    }

    @Override
    public byte[] seekCeil(byte[] target) {
        seekCount++;
        int lo = 0;
        int hi = terms.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Arrays.compareUnsigned(terms[mid], target) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        pos = lo;
        return pos < terms.length ? terms[pos] : null;
    }

    @Override
    public byte[] next() {
        nextCount++;
        if (pos >= terms.length) {
            return null;
        }
        pos++;
        return pos < terms.length ? terms[pos] : null;
    }

    public int getSeekCount() {
        return seekCount;
    }

    public int getNextCount() {
        return nextCount;
    }

    public int size() {
        return terms.length;
    }
}
