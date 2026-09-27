package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class PointRangeQueryTest {

    @Test
    public void longRangeMatchesBruteForceFiltering() throws Exception {
        int maxDoc = 200;
        long[] values = new long[maxDoc];
        boolean[] present = new boolean[maxDoc];
        Random random = new Random(42);
        for (int d = 0; d < maxDoc; d++) {
            present[d] = random.nextInt(10) != 0;
            values[d] = random.nextInt(1000) - 500;
        }
        var bkd = TestSegments.buildLongPoints(maxDoc, values, present);
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc)
            .points("price", bkd)
            .build();
        IndexSearcher searcher = new IndexSearcher(List.of(reader));

        long lower = -100;
        long upper = 250;
        PointRangeQuery query = PointRangeQuery.newLongRange("price", lower, upper);
        TopDocs topDocs = searcher.search(query, maxDoc);

        Set<Integer> actual = new TreeSet<>();
        for (ScoreDoc sd : topDocs.scoreDocs()) {
            actual.add(sd.doc);
        }

        Set<Integer> expected = new TreeSet<>();
        for (int d = 0; d < maxDoc; d++) {
            if (present[d] && values[d] >= lower && values[d] <= upper) {
                expected.add(d);
            }
        }
        assertEquals(expected, actual);
        assertEquals(true, expected.size() > 0 && expected.size() < maxDoc);
    }

    @Test
    public void intRangeMatchesBruteForce() throws Exception {
        int maxDoc = 50;
        long[] values = new long[maxDoc];
        for (int d = 0; d < maxDoc; d++) {
            values[d] = d;
        }
        var bkd = TestSegments.buildLongPoints(maxDoc, values, null);
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc).points("n", bkd).build();
        IndexSearcher searcher = new IndexSearcher(List.of(reader));

        PointRangeQuery query = PointRangeQuery.newLongRange("n", 10, 15);
        TopDocs topDocs = searcher.search(query, maxDoc);
        Set<Integer> actual = new TreeSet<>();
        for (ScoreDoc sd : topDocs.scoreDocs()) {
            actual.add(sd.doc);
        }
        assertEquals(Set.of(10, 11, 12, 13, 14, 15), actual);
    }
}
