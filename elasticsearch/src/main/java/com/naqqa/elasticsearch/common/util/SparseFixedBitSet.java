package com.naqqa.elasticsearch.common.util;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

public final class SparseFixedBitSet {

    private final int numBits;
    private final Map<Integer, Long> words = new HashMap<>();

    public SparseFixedBitSet(int numBits) {
        this.numBits = numBits;
    }

    public int length() {
        return numBits;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= numBits) {
            throw new IndexOutOfBoundsException("index=" + index + ", numBits=" + numBits);
        }
    }

    public boolean get(int index) {
        checkIndex(index);
        Long word = words.get(index >> 6);
        if (word == null) {
            return false;
        }
        return (word & (1L << (index & 0x3F))) != 0;
    }

    public void set(int index) {
        checkIndex(index);
        int wordNum = index >> 6;
        long mask = 1L << (index & 0x3F);
        words.merge(wordNum, mask, (a, b) -> a | b);
    }

    public void clear(int index) {
        checkIndex(index);
        int wordNum = index >> 6;
        Long word = words.get(wordNum);
        if (word == null) {
            return;
        }
        long updated = word & ~(1L << (index & 0x3F));
        if (updated == 0) {
            words.remove(wordNum);
        } else {
            words.put(wordNum, updated);
        }
    }

    public int cardinality() {
        int sum = 0;
        for (long w : words.values()) {
            sum += Long.bitCount(w);
        }
        return sum;
    }

    public int nextSetBit(int index) {
        if (index >= numBits) {
            return -1;
        }
        int startWord = index >> 6;
        TreeSet<Integer> sortedWordNums = new TreeSet<>(words.keySet());
        Integer candidate = sortedWordNums.ceiling(startWord);
        while (candidate != null) {
            long word = words.get(candidate);
            if (candidate == startWord) {
                word &= (~0L << (index & 0x3F));
            }
            if (word != 0) {
                int bit = candidate * 64 + Long.numberOfTrailingZeros(word);
                return bit < numBits ? bit : -1;
            }
            candidate = sortedWordNums.higher(candidate);
        }
        return -1;
    }

    public long ramBytesUsed() {
        return 48L + words.size() * 48L;
    }
}
