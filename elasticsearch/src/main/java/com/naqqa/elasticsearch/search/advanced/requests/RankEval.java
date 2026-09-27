package com.naqqa.elasticsearch.search.advanced.requests;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.query.Query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RankEval {

    private RankEval() {
    }

    public record Metrics(double precisionAtK, double dcg, double idcg, double ndcg, double reciprocalRank) {
    }

    public static Metrics evaluate(IndexSearcher searcher, Query query, Map<Integer, Integer> ratings, int k) throws IOException {
        TopDocs topDocs = searcher.search(query, k);
        ScoreDoc[] hits = topDocs.scoreDocs();
        int relevantCount = 0;
        double dcg = 0;
        double reciprocalRank = 0;
        boolean foundFirstRelevant = false;
        int limit = Math.min(hits.length, k);
        for (int i = 0; i < limit; i++) {
            int rank = i + 1;
            int rel = ratings.getOrDefault(hits[i].doc, 0);
            if (rel > 0) {
                relevantCount++;
                if (!foundFirstRelevant) {
                    reciprocalRank = 1.0 / rank;
                    foundFirstRelevant = true;
                }
            }
            dcg += rel / log2(rank + 1);
        }
        double precisionAtK = k == 0 ? 0 : (double) relevantCount / k;
        double idcg = idealDcg(ratings, k);
        double ndcg = idcg == 0 ? 0 : dcg / idcg;
        return new Metrics(precisionAtK, dcg, idcg, ndcg, reciprocalRank);
    }

    private static double idealDcg(Map<Integer, Integer> ratings, int k) {
        List<Integer> sorted = new ArrayList<>(ratings.values());
        sorted.sort((a, b) -> Integer.compare(b, a));
        double idcg = 0;
        int limit = Math.min(sorted.size(), k);
        for (int i = 0; i < limit; i++) {
            int rel = sorted.get(i);
            if (rel <= 0) {
                continue;
            }
            idcg += rel / log2(i + 2);
        }
        return idcg;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
