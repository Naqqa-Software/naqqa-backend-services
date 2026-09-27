package com.naqqa.elasticsearch.search.vectors;

import java.util.Arrays;

public final class NeighborQueue {

    private final boolean maxHeap;
    private long[] heap;
    private int size;

    public NeighborQueue(int initialSize, boolean maxHeap) {
        this.maxHeap = maxHeap;
        this.heap = new long[Math.max(2, initialSize + 1)];
    }

    public static int sortableFloatBits(float value) {
        int bits = Float.floatToIntBits(value);
        return bits ^ ((bits >> 31) & 0x7fffffff);
    }

    public static float sortableBitsToFloat(int bits) {
        return Float.intBitsToFloat(bits ^ ((bits >> 31) & 0x7fffffff));
    }

    private long encode(int node, float score) {
        long encoded = (((long) sortableFloatBits(score)) << 32) | (0xFFFFFFFFL & ~node);
        return maxHeap ? -encoded : encoded;
    }

    private long decodeRaw(long stored) {
        return maxHeap ? -stored : stored;
    }

    private static int decodeNode(long raw) {
        return ~((int) raw);
    }

    private static float decodeScore(long raw) {
        return sortableBitsToFloat((int) (raw >> 32));
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

    public void add(int node, float score) {
        push(encode(node, score));
    }

    public boolean insertWithOverflow(int node, float score, int maxSize) {
        long value = encode(node, score);
        if (size < maxSize) {
            push(value);
            return true;
        }
        if (size > 0 && value > heap[1]) {
            heap[1] = value;
            downHeap(1);
            return true;
        }
        return false;
    }

    public int topNode() {
        return decodeNode(decodeRaw(heap[1]));
    }

    public float topScore() {
        return decodeScore(decodeRaw(heap[1]));
    }

    public int pop() {
        long top = heap[1];
        heap[1] = heap[size];
        size--;
        if (size > 0) {
            downHeap(1);
        }
        return decodeNode(decodeRaw(top));
    }

    public int[] nodes() {
        int[] out = new int[size];
        for (int i = 0; i < size; i++) {
            out[i] = decodeNode(decodeRaw(heap[i + 1]));
        }
        return out;
    }

    private void push(long value) {
        size++;
        if (size >= heap.length) {
            heap = Arrays.copyOf(heap, heap.length * 2);
        }
        heap[size] = value;
        upHeap(size);
    }

    private void upHeap(int origPos) {
        int i = origPos;
        long value = heap[i];
        int j = i >>> 1;
        while (j > 0 && value < heap[j]) {
            heap[i] = heap[j];
            i = j;
            j = j >>> 1;
        }
        heap[i] = value;
    }

    private void downHeap(int i) {
        long value = heap[i];
        int j = i << 1;
        int k = j + 1;
        if (k <= size && heap[k] < heap[j]) {
            j = k;
        }
        while (j <= size && heap[j] < value) {
            heap[i] = heap[j];
            i = j;
            j = i << 1;
            k = j + 1;
            if (k <= size && heap[k] < heap[j]) {
                j = k;
            }
        }
        heap[i] = value;
    }
}
