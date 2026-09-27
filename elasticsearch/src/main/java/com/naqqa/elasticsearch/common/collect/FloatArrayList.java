package com.naqqa.elasticsearch.common.collect;

import java.util.Arrays;
import java.util.NoSuchElementException;

public final class FloatArrayList {

    private float[] buffer;
    private int size;

    public FloatArrayList() {
        this(16);
    }

    public FloatArrayList(int initialCapacity) {
        buffer = new float[Math.max(initialCapacity, 4)];
    }

    public void add(float value) {
        ensureCapacity(size + 1);
        buffer[size++] = value;
    }

    public float get(int index) {
        if (index < 0 || index >= size) {
            throw new NoSuchElementException("index=" + index);
        }
        return buffer[index];
    }

    public void set(int index, float value) {
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

    public float[] toArray() {
        return Arrays.copyOf(buffer, size);
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, minCapacity));
        }
    }
}
