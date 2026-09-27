package com.naqqa.elasticsearch.search.vectors;

import java.util.Arrays;

public final class KnnResults {

    private static final KnnResults EMPTY = new KnnResults(new int[0], new int[0], new float[0], 0, false, false);

    private final int[] ords;
    private final int[] docs;
    private final float[] scores;
    private final long visitedCount;
    private final boolean incomplete;
    private final boolean exact;

    public KnnResults(int[] ords, int[] docs, float[] scores, long visitedCount, boolean incomplete, boolean exact) {
        if (ords.length != docs.length || docs.length != scores.length) {
            throw new IllegalArgumentException("inconsistent result arrays");
        }
        this.ords = ords;
        this.docs = docs;
        this.scores = scores;
        this.visitedCount = visitedCount;
        this.incomplete = incomplete;
        this.exact = exact;
    }

    public static KnnResults empty() {
        return EMPTY;
    }

    public static KnnResults fromMinHeap(NeighborQueue heap, RandomVectorScorer scorer, long visitedCount, boolean incomplete, boolean exact) {
        int n = heap.size();
        int[] ords = new int[n];
        int[] docs = new int[n];
        float[] scores = new float[n];
        for (int i = n - 1; i >= 0; i--) {
            scores[i] = heap.topScore();
            ords[i] = heap.pop();
            docs[i] = scorer.ordToDoc(ords[i]);
        }
        return new KnnResults(ords, docs, scores, visitedCount, incomplete, exact);
    }

    public int size() {
        return docs.length;
    }

    public int ord(int i) {
        return ords[i];
    }

    public int doc(int i) {
        return docs[i];
    }

    public float score(int i) {
        return scores[i];
    }

    public int[] ords() {
        return ords.clone();
    }

    public int[] docs() {
        return docs.clone();
    }

    public float[] scores() {
        return scores.clone();
    }

    public long visitedCount() {
        return visitedCount;
    }

    public boolean incomplete() {
        return incomplete;
    }

    public boolean exact() {
        return exact;
    }

    public KnnResults topK(int k) {
        if (k >= docs.length) {
            return this;
        }
        return new KnnResults(Arrays.copyOf(ords, k), Arrays.copyOf(docs, k), Arrays.copyOf(scores, k), visitedCount, incomplete, exact);
    }

    public KnnResults withMinScore(float minScore) {
        int n = 0;
        while (n < scores.length && scores[n] >= minScore) {
            n++;
        }
        return topK(n);
    }

    public KnnResults withVisitedCount(long visited) {
        return new KnnResults(ords, docs, scores, visited, incomplete, exact);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("KnnResults[");
        for (int i = 0; i < docs.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(docs[i]).append(':').append(scores[i]);
        }
        return sb.append("; visited=").append(visitedCount).append(exact ? ", exact" : "").append(']').toString();
    }
}
