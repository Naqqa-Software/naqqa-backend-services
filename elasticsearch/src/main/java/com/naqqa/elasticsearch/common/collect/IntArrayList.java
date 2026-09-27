package com.naqqa.elasticsearch.common.collect;

import java.util.Arrays;
import java.util.NoSuchElementException;

public final class IntArrayList {

    private int[] buffer;
    private int size;

    public IntArrayList() {
        this(16);
    }

    public IntArrayList(int initialCapacity) {
        buffer = new int[Math.max(initialCapacity, 4)];
    }

    public void add(int value) {
        ensureCapacity(size + 1);
        buffer[size++] = value;
    }

    public int get(int index) {
        if (index < 0 || index >= size) {
            throw new NoSuchElementException("index=" + index);
        }
        return buffer[index];
    }

    public void set(int index, int value) {
        if (index < 0 || index >= size) {
            throw new NoSuchElementException("index=" + index);
        }
        buffer[index] = value;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void clear() {
        size = 0;
    }

    public int[] toArray() {
        return Arrays.copyOf(buffer, size);
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, minCapacity));
        }
    }
}
