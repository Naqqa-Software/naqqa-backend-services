package com.naqqa.elasticsearch.search.execution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public final class TopFieldCollectorManager implements CollectorManager<TopFieldCollector, TopDocs> {

    private final Sort sort;
    private final int numHits;
    private final int totalHitsThreshold;

    public TopFieldCollectorManager(Sort sort, int numHits) {
        this(sort, numHits, TopFieldCollector.TRACK_TOTAL_HITS_ACCURATE);
    }

    public TopFieldCollectorManager(Sort sort, int numHits, int totalHitsThreshold) {
        this.sort = sort;
        this.numHits = numHits;
        this.totalHitsThreshold = totalHitsThreshold;
    }

    @Override
    public TopFieldCollector newCollector() {
        return TopFieldCollector.create(sort, numHits, totalHitsThreshold);
    }

    @Override
    public TopDocs reduce(Collection<TopFieldCollector> collectors) {
        List<FieldDoc> merged = new ArrayList<>();
        long totalHits = 0;
        boolean anyGreaterOrEqual = false;
        for (TopFieldCollector collector : collectors) {
            FieldTopDocs fieldTopDocs = collector.fieldTopDocs();
            totalHits += fieldTopDocs.totalHits().value();
            if (fieldTopDocs.totalHits().relation() == TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO) {
                anyGreaterOrEqual = true;
            }
            merged.addAll(Arrays.asList(fieldTopDocs.fieldDocs()));
        }
        merged.sort((a, b) -> TopFieldCollector.compareFieldDocs(sort, a, b));
        int size = Math.min(numHits, merged.size());
        ScoreDoc[] docs = new ScoreDoc[size];
        for (int i = 0; i < size; i++) {
            FieldDoc fd = merged.get(i);
            docs[i] = new ScoreDoc(fd.doc, fd.score);
        }
        TotalHits.Relation relation = TotalHits.Relation.EQUAL_TO;
        if (totalHitsThreshold != TopFieldCollector.TRACK_TOTAL_HITS_ACCURATE
            && (anyGreaterOrEqual || totalHits > totalHitsThreshold)) {
            relation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
        }
        return new TopDocs(new TotalHits(totalHits, relation), docs);
    }
}
