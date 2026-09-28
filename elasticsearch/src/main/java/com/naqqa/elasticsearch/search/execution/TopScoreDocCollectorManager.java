package com.naqqa.elasticsearch.search.execution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public final class TopScoreDocCollectorManager implements CollectorManager<TopScoreDocCollector, TopDocs> {

    private final int numHits;
    private final int totalHitsThreshold;
    private final MaxScoreAccumulator minScoreAcc;

    public TopScoreDocCollectorManager(int numHits) {
        this(numHits, TopScoreDocCollector.TRACK_TOTAL_HITS_ACCURATE);
    }

    public TopScoreDocCollectorManager(int numHits, int totalHitsThreshold) {
        this.numHits = numHits;
        this.totalHitsThreshold = totalHitsThreshold;
        this.minScoreAcc = numHits > 0 ? new MaxScoreAccumulator() : null;
    }

    @Override
    public TopScoreDocCollector newCollector() {
        return TopScoreDocCollector.createShared(numHits, totalHitsThreshold, minScoreAcc);
    }

    @Override
    public TopDocs reduce(Collection<TopScoreDocCollector> collectors) {
        List<ScoreDoc> merged = new ArrayList<>();
        long totalHits = 0;
        boolean anyGreaterOrEqual = false;
        for (TopScoreDocCollector collector : collectors) {
            TopDocs topDocs = collector.topDocs();
            totalHits += topDocs.totalHits().value();
            if (topDocs.totalHits().relation() == TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO) {
                anyGreaterOrEqual = true;
            }
            merged.addAll(Arrays.asList(topDocs.scoreDocs()));
        }
        merged.sort((a, b) -> {
            if (a.score != b.score) {
                return Float.compare(b.score, a.score);
            }
            return Integer.compare(a.doc, b.doc);
        });
        int size = Math.min(numHits, merged.size());
        ScoreDoc[] docs = merged.subList(0, size).toArray(new ScoreDoc[0]);
        TotalHits.Relation relation = TotalHits.Relation.EQUAL_TO;
        if (totalHitsThreshold != TopScoreDocCollector.TRACK_TOTAL_HITS_ACCURATE
            && (anyGreaterOrEqual || totalHits > totalHitsThreshold)) {
            relation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
        }
        return new TopDocs(new TotalHits(totalHits, relation), docs);
    }
}
