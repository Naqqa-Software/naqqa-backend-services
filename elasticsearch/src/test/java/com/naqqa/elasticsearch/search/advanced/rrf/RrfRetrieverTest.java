package com.naqqa.elasticsearch.search.advanced.rrf;

import com.naqqa.elasticsearch.test.Test;

import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class RrfRetrieverTest {

    @Test
    public void fusesTwoRankedListsUsingReciprocalRankFormula() {
        List<Integer> listA = List.of(1, 2, 3);
        List<Integer> listB = List.of(2, 1, 4);
        List<RrfRetriever.RrfHit> hits = RrfRetriever.fuse(List.of(listA, listB), 60, 10);

        double scoreDoc1 = 1.0 / (60 + 1) + 1.0 / (60 + 2);
        double scoreDoc2 = 1.0 / (60 + 2) + 1.0 / (60 + 1);
        double scoreDoc3 = 1.0 / (60 + 3);

        assertEquals(4, hits.size());
        assertEquals(1, hits.get(0).docId());
        assertEquals(2, hits.get(1).docId());
        assertEquals(3, hits.get(2).docId());
        assertEquals(4, hits.get(3).docId());
        assertEquals(scoreDoc1, hits.get(0).score(), 1e-9);
        assertEquals(scoreDoc2, hits.get(1).score(), 1e-9);
        assertEquals(scoreDoc3, hits.get(2).score(), 1e-9);
        assertEquals(scoreDoc3, hits.get(3).score(), 1e-9);
    }

    @Test
    public void sizeTruncatesFusedResults() {
        List<Integer> listA = List.of(10, 20, 30, 40);
        List<RrfRetriever.RrfHit> hits = RrfRetriever.fuse(List.of(listA), 20, 2);
        assertEquals(2, hits.size());
        assertEquals(10, hits.get(0).docId());
        assertEquals(20, hits.get(1).docId());
    }
}
