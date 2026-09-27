package com.naqqa.elasticsearch.search.vectors.hnsw;

import com.naqqa.elasticsearch.search.vectors.Bits;
import com.naqqa.elasticsearch.search.vectors.KnnResults;
import com.naqqa.elasticsearch.search.vectors.NeighborQueue;
import com.naqqa.elasticsearch.search.vectors.RandomVectorScorer;

public final class HnswGraphSearcher {

    private final VisitedSet visited;
    private final NeighborQueue candidates;
    private long visitedCount;
    private boolean incomplete;

    public HnswGraphSearcher(int capacity) {
        this.visited = new VisitedSet(capacity);
        this.candidates = new NeighborQueue(100, true);
    }

    public long visitedCount() {
        return visitedCount;
    }

    public boolean incomplete() {
        return incomplete;
    }

    public void resetCounters() {
        visitedCount = 0;
        incomplete = false;
    }

    public static KnnResults search(RandomVectorScorer scorer, int topK, HnswGraph graph, Bits acceptOrds, long visitedLimit) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got " + topK);
        }
        HnswGraphSearcher searcher = new HnswGraphSearcher(graph.maxNodeId() + 1);
        NeighborQueue results = searcher.searchGraph(scorer, topK, graph, acceptOrds, visitedLimit);
        return KnnResults.fromMinHeap(results, scorer, searcher.visitedCount, searcher.incomplete, false);
    }

    public NeighborQueue searchGraph(RandomVectorScorer scorer, int topK, HnswGraph graph, Bits acceptOrds, long visitedLimit) {
        resetCounters();
        NeighborQueue results = new NeighborQueue(topK, false);
        int entry = graph.entryNode();
        if (entry < 0 || graph.size() == 0) {
            return results;
        }
        int ep = findBestEntryPoint(scorer, graph, visitedLimit);
        if (ep < 0 || incomplete) {
            return results;
        }
        searchLevel(results, scorer, topK, 0, new int[] {ep}, graph, acceptOrds, visitedLimit);
        return results;
    }

    private int findBestEntryPoint(RandomVectorScorer scorer, HnswGraph graph, long visitedLimit) {
        int currentEp = graph.entryNode();
        if (visitedCount >= visitedLimit) {
            incomplete = true;
            return -1;
        }
        float currentScore = scorer.score(currentEp);
        visitedCount++;
        for (int level = graph.numLevels() - 1; level >= 1; level--) {
            visited.clear();
            visited.getAndSet(currentEp);
            boolean foundBetter = true;
            while (foundBetter) {
                foundBetter = false;
                NeighborArray neighbors = graph.neighbors(level, currentEp);
                int n = neighbors.size();
                int bestNode = currentEp;
                for (int i = 0; i < n; i++) {
                    int friend = neighbors.node(i);
                    if (visited.getAndSet(friend)) {
                        continue;
                    }
                    if (visitedCount >= visitedLimit) {
                        incomplete = true;
                        return currentEp;
                    }
                    float score = scorer.score(friend);
                    visitedCount++;
                    if (score > currentScore) {
                        currentScore = score;
                        bestNode = friend;
                        foundBetter = true;
                    }
                }
                currentEp = bestNode;
            }
        }
        return currentEp;
    }

    public NeighborQueue searchLevel(RandomVectorScorer scorer, int topK, int level, int[] eps, HnswGraph graph) {
        NeighborQueue results = new NeighborQueue(topK, false);
        searchLevel(results, scorer, topK, level, eps, graph, null, Long.MAX_VALUE);
        return results;
    }

    public void searchLevel(NeighborQueue results, RandomVectorScorer scorer, int topK, int level, int[] eps, HnswGraph graph, Bits acceptOrds, long visitedLimit) {
        visited.clear();
        candidates.clear();
        for (int ep : eps) {
            if (visited.getAndSet(ep)) {
                continue;
            }
            if (visitedCount >= visitedLimit) {
                incomplete = true;
                return;
            }
            float score = scorer.score(ep);
            visitedCount++;
            candidates.add(ep, score);
            if (acceptOrds == null || acceptOrds.get(ep)) {
                results.insertWithOverflow(ep, score, topK);
            }
        }
        float minAccepted = results.size() >= topK ? results.topScore() : Float.NEGATIVE_INFINITY;
        while (candidates.size() > 0) {
            float topCandidateScore = candidates.topScore();
            if (topCandidateScore < minAccepted) {
                break;
            }
            int topCandidate = candidates.pop();
            NeighborArray neighbors = graph.neighbors(level, topCandidate);
            int n = neighbors.size();
            for (int i = 0; i < n; i++) {
                int friend = neighbors.node(i);
                if (visited.getAndSet(friend)) {
                    continue;
                }
                if (visitedCount >= visitedLimit) {
                    incomplete = true;
                    return;
                }
                float friendScore = scorer.score(friend);
                visitedCount++;
                if (friendScore >= minAccepted) {
                    candidates.add(friend, friendScore);
                    if ((acceptOrds == null || acceptOrds.get(friend))
                        && results.insertWithOverflow(friend, friendScore, topK)
                        && results.size() >= topK) {
                        minAccepted = results.topScore();
                    }
                }
            }
        }
    }
}
