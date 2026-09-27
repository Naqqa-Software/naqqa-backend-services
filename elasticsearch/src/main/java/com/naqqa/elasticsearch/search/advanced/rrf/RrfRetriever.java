package com.naqqa.elasticsearch.search.advanced.rrf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RrfRetriever {

    private RrfRetriever() {
    }

    public record RrfHit(int docId, double score) {
    }

    public static List<RrfHit> fuse(List<List<Integer>> rankedLists, int rankConstant, int size) {
        Map<Integer, Double> scores = new LinkedHashMap<>();
        for (List<Integer> list : rankedLists) {
            int rank = 1;
            for (Integer docId : list) {
                scores.merge(docId, 1.0 / (rankConstant + rank), Double::sum);
                rank++;
            }
        }
        List<RrfHit> hits = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : scores.entrySet()) {
            hits.add(new RrfHit(e.getKey(), e.getValue()));
        }
        hits.sort((a, b) -> {
            int cmp = Double.compare(b.score(), a.score());
            return cmp != 0 ? cmp : Integer.compare(a.docId(), b.docId());
        });
        if (size >= 0 && hits.size() > size) {
            hits = hits.subList(0, size);
        }
        return hits;
    }
}
