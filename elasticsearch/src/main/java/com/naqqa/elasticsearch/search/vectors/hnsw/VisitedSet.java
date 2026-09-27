package com.naqqa.elasticsearch.search.vectors.hnsw;

import java.util.Arrays;

final class VisitedSet {

    private long[] words;
    private int[] touched;
    private int touchedCount;
    private boolean overflow;

    VisitedSet(int capacity) {
        words = new long[Math.max(1, (capacity + 63) >>> 6)];
        touched = new int[64];
    }

    boolean getAndSet(int index) {
        int w = index >>> 6;
        if (w >= words.length) {
            words = Arrays.copyOf(words, Math.max(w + 1, words.length * 2));
        }
        long mask = 1L << index;
        long word = words[w];
        if ((word & mask) != 0) {
            return true;
        }
        if (word == 0 && !overflow) {
            if (touchedCount == touched.length) {
                if (touched.length >= words.length / 8) {
                    overflow = true;
                } else {
                    touched = Arrays.copyOf(touched, touched.length * 2);
                }
            }
            if (!overflow) {
                touched[touchedCount++] = w;
            }
        }
        words[w] = word | mask;
        return false;
    }

    void clear() {
        if (overflow) {
            Arrays.fill(words, 0L);
        } else {
            for (int i = 0; i < touchedCount; i++) {
                words[touched[i]] = 0L;
            }
        }
        touchedCount = 0;
        overflow = false;
    }
}
