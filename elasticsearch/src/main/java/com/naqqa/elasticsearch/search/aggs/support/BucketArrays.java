package com.naqqa.elasticsearch.search.aggs.support;

import java.util.Arrays;

public final class BucketArrays {

    private BucketArrays() {
    }

    public static double[] grow(double[] array, int minSize) {
        if (array.length >= minSize) {
            return array;
        }
        return Arrays.copyOf(array, Math.max(minSize, array.length * 2 + 1));
    }

    public static long[] grow(long[] array, int minSize) {
        if (array.length >= minSize) {
            return array;
        }
        return Arrays.copyOf(array, Math.max(minSize, array.length * 2 + 1));
    }

    public static int[] grow(int[] array, int minSize) {
        if (array.length >= minSize) {
            return array;
        }
        return Arrays.copyOf(array, Math.max(minSize, array.length * 2 + 1));
    }

    public static boolean[] grow(boolean[] array, int minSize) {
        if (array.length >= minSize) {
            return array;
        }
        return Arrays.copyOf(array, Math.max(minSize, array.length * 2 + 1));
    }

    @SuppressWarnings("unchecked")
    public static <T> T[] grow(T[] array, int minSize) {
        if (array.length >= minSize) {
            return array;
        }
        return Arrays.copyOf(array, Math.max(minSize, array.length * 2 + 1));
    }
}
