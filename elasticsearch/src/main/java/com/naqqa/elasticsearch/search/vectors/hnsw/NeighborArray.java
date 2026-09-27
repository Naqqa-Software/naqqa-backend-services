package com.naqqa.elasticsearch.search.vectors.hnsw;

public final class NeighborArray {

    private final int[] nodes;
    private final float[] scores;
    private int size;

    public NeighborArray(int capacity) {
        this.nodes = new int[capacity];
        this.scores = new float[capacity];
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return nodes.length;
    }

    public int node(int i) {
        return nodes[i];
    }

    public float score(int i) {
        return scores[i];
    }

    public int[] nodesCopy() {
        return java.util.Arrays.copyOf(nodes, size);
    }

    public boolean contains(int node) {
        for (int i = 0; i < size; i++) {
            if (nodes[i] == node) {
                return true;
            }
        }
        return false;
    }

    public void addInOrder(int node, float score) {
        if (size == nodes.length) {
            throw new IllegalStateException("neighbor array is full");
        }
        if (size > 0 && score > scores[size - 1]) {
            throw new IllegalArgumentException("nodes must be added in descending score order");
        }
        nodes[size] = node;
        scores[size] = score;
        size++;
    }

    void append(int node, float score) {
        if (size == nodes.length) {
            throw new IllegalStateException("neighbor array is full");
        }
        nodes[size] = node;
        scores[size] = score;
        size++;
    }

    public void insertSorted(int node, float score) {
        if (size == nodes.length) {
            throw new IllegalStateException("neighbor array is full");
        }
        int pos = size;
        while (pos > 0 && (scores[pos - 1] < score || (scores[pos - 1] == score && nodes[pos - 1] > node))) {
            nodes[pos] = nodes[pos - 1];
            scores[pos] = scores[pos - 1];
            pos--;
        }
        nodes[pos] = node;
        scores[pos] = score;
        size++;
    }

    public void removeIndex(int index) {
        System.arraycopy(nodes, index + 1, nodes, index, size - index - 1);
        System.arraycopy(scores, index + 1, scores, index, size - index - 1);
        size--;
    }

    public void clear() {
        size = 0;
    }
}
