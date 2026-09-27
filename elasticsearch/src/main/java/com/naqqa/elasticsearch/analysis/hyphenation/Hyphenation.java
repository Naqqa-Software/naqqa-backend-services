package com.naqqa.elasticsearch.analysis.hyphenation;

import java.util.Arrays;

public final class Hyphenation {

    private final int[] hyphenPoints;

    Hyphenation(int[] points) {
        this.hyphenPoints = points;
    }

    public int length() {
        return hyphenPoints.length;
    }

    public int[] getHyphenationPoints() {
        return hyphenPoints;
    }

    @Override
    public String toString() {
        return "Hyphenation" + Arrays.toString(hyphenPoints);
    }
}
