package com.naqqa.elasticsearch.analysis;

import java.util.Arrays;

public final class FilteredText implements OffsetCorrector {

    private final StringBuilder text = new StringBuilder();
    private int[] offsets = new int[8];
    private int[] diffs = new int[8];
    private int size;
    private OffsetCorrector parent = OffsetCorrector.IDENTITY;

    public void reset(OffsetCorrector parent) {
        text.setLength(0);
        size = 0;
        this.parent = parent == null ? OffsetCorrector.IDENTITY : parent;
    }

    public StringBuilder text() {
        return text;
    }

    public void addOffCorrectMap(int off, int cumulativeDiff) {
        if (size > 0 && off < offsets[size - 1]) {
            throw new IllegalArgumentException("Offset #" + size + "(" + off + ") is less than the last recorded offset " + offsets[size - 1]);
        }
        if (size == 0 || off != offsets[size - 1]) {
            if (size == offsets.length) {
                offsets = Arrays.copyOf(offsets, size * 2);
                diffs = Arrays.copyOf(diffs, size * 2);
            }
            offsets[size] = off;
            diffs[size++] = cumulativeDiff;
        } else {
            diffs[size - 1] = cumulativeDiff;
        }
    }

    public int lastCumulativeDiff() {
        return size == 0 ? 0 : diffs[size - 1];
    }

    public int correct(int currentOff) {
        if (size == 0) {
            return currentOff;
        }
        int index = Arrays.binarySearch(offsets, 0, size, currentOff);
        if (index < -1) {
            index = -2 - index;
        }
        int diff = index < 0 ? 0 : diffs[index];
        return currentOff + diff;
    }

    @Override
    public int correctOffset(int offset) {
        return parent.correctOffset(correct(offset));
    }
}
