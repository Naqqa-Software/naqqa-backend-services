package com.naqqa.elasticsearch.search.vectors.hnsw;

import com.naqqa.elasticsearch.search.vectors.Bits;
import com.naqqa.elasticsearch.search.vectors.ExactKnnSearcher;
import com.naqqa.elasticsearch.search.vectors.KnnResults;
import com.naqqa.elasticsearch.search.vectors.RandomVectorScorer;

public final class KnnSearch {

    private KnnSearch() {
    }

    public static KnnResults search(RandomVectorScorer scorer, HnswGraph graph, int k, int numCandidates, Bits acceptDocs) {
        return searchCandidates(scorer, graph, Math.max(k, numCandidates), acceptDocs).topK(k);
    }

    public static KnnResults searchCandidates(RandomVectorScorer scorer, HnswGraph graph, int topK, Bits acceptDocs) {
        if (topK <= 0) {
            throw new IllegalArgumentException("k must be > 0, got " + topK);
        }
        if (scorer.maxOrd() == 0) {
            return KnnResults.empty();
        }
        Bits acceptOrds = scorer.acceptOrds(acceptDocs);
        if (graph == null || graph.size() == 0) {
            return ExactKnnSearcher.search(scorer, topK, acceptOrds);
        }
        if (acceptOrds == null) {
            return HnswGraphSearcher.search(scorer, topK, graph, null, Long.MAX_VALUE);
        }
        int cost = 0;
        int maxOrd = scorer.maxOrd();
        for (int ord = 0; ord < maxOrd; ord++) {
            if (acceptOrds.get(ord)) {
                cost++;
            }
        }
        if (cost == 0) {
            return KnnResults.empty();
        }
        if (cost <= topK) {
            return ExactKnnSearcher.search(scorer, topK, acceptOrds);
        }
        KnnResults approximate = HnswGraphSearcher.search(scorer, topK, graph, acceptOrds, cost);
        if (approximate.incomplete()) {
            KnnResults exact = ExactKnnSearcher.search(scorer, topK, acceptOrds);
            return exact.withVisitedCount(exact.visitedCount() + approximate.visitedCount());
        }
        return approximate;
    }
}
