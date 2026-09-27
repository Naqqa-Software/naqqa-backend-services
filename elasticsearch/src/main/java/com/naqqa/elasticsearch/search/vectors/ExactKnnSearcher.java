package com.naqqa.elasticsearch.search.vectors;

import java.util.function.IntPredicate;

public final class ExactKnnSearcher {

    private ExactKnnSearcher() {
    }

    public static KnnResults search(RandomVectorScorer scorer, int k, Bits acceptOrds) {
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0, got " + k);
        }
        int maxOrd = scorer.maxOrd();
        NeighborQueue heap = new NeighborQueue(Math.min(k, Math.max(1, maxOrd)), false);
        long visited = 0;
        for (int ord = 0; ord < maxOrd; ord++) {
            if (acceptOrds != null && !acceptOrds.get(ord)) {
                continue;
            }
            float score = scorer.score(ord);
            visited++;
            heap.insertWithOverflow(ord, score, k);
        }
        return KnnResults.fromMinHeap(heap, scorer, visited, false, true);
    }

    public static KnnResults searchDocs(RandomVectorScorer scorer, int k, Bits acceptDocs) {
        return search(scorer, k, scorer.acceptOrds(acceptDocs));
    }

    public static KnnResults search(float[][] vectors, float[] query, VectorSimilarity similarity, int k, IntPredicate filter) {
        RandomVectorScorer scorer = VectorScorers.floatScorer(FloatVectorValues.of(vectors), similarity, query);
        return search(scorer, k, filter == null ? null : Bits.fromPredicate(filter, vectors.length));
    }

    public static KnnResults search(byte[][] vectors, byte[] query, VectorSimilarity similarity, int k, IntPredicate filter) {
        RandomVectorScorer scorer = VectorScorers.byteScorer(ByteVectorValues.of(vectors), similarity, query);
        return search(scorer, k, filter == null ? null : Bits.fromPredicate(filter, vectors.length));
    }

    public static KnnResults searchBits(byte[][] vectors, byte[] query, int k, IntPredicate filter) {
        RandomVectorScorer scorer = VectorScorers.byteScorer(ByteVectorValues.bits(vectors, null), VectorSimilarity.L2_NORM, query);
        return search(scorer, k, filter == null ? null : Bits.fromPredicate(filter, vectors.length));
    }

    public static KnnResults rescore(KnnResults candidates, RandomVectorScorer exactScorer, int k) {
        NeighborQueue heap = new NeighborQueue(Math.max(1, Math.min(k, candidates.size())), false);
        for (int i = 0; i < candidates.size(); i++) {
            int ord = candidates.ord(i);
            heap.insertWithOverflow(ord, exactScorer.score(ord), k);
        }
        return KnnResults.fromMinHeap(heap, exactScorer, candidates.visitedCount() + candidates.size(), candidates.incomplete(), candidates.exact());
    }
}
