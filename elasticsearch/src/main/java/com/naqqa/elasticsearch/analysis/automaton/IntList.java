package com.naqqa.elasticsearch.analysis.automaton;

import java.util.Arrays;

final class IntList {

    int[] values;
    int size;

    IntList() {
        values = new int[8];
    }

    IntList(int capacity) {
        values = new int[Math.max(capacity, 1)];
    }

    void add(int v) {
        if (size == values.length) {
            values = Arrays.copyOf(values, size + (size >> 1) + 8);
        }
        values[size++] = v;
    }

    void add(int a, int b, int c) {
        if (size + 3 > values.length) {
            values = Arrays.copyOf(values, size + (size >> 1) + 12);
        }
        values[size++] = a;
        values[size++] = b;
        values[size++] = c;
    }

    int get(int i) {
        return values[i];
    }

    int size() {
        return size;
    }

    void clear() {
        size = 0;
    }

    int[] toArray() {
        return Arrays.copyOf(values, size);
    }
}
