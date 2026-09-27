package com.naqqa.elasticsearch.search.advanced.pagination;

import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.SortField;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class PaginationTest {

    private static IndexSearcher buildSearcher(int maxDoc) throws Exception {
        long[] values = new long[maxDoc];
        for (int i = 0; i < maxDoc; i++) {
            values[i] = i;
        }
        NumericDocValuesReader ndv = TestSegments.buildNumericDocValues(maxDoc, values, null);
        FieldInfo fi = new FieldInfo("num", 0, false, 0, false, false, false, DocValuesType.NUMERIC, 0, 0, Map.of());
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc)
            .field("num", fi)
            .numericDocValues("num", ndv)
            .build();
        return new IndexSearcher(List.of(reader));
    }

    @Test
    public void searchAfterProducesFullCoverageWithoutOverlapOrGaps() throws Exception {
        int maxDoc = 23;
        IndexSearcher searcher = buildSearcher(maxDoc);
        Sort sort = new Sort(new SortField("num", SortField.Type.LONG));

        Pagination.Page baseline = Pagination.searchAfter(searcher, new MatchAllDocsQuery(), sort, maxDoc, null);
        assertEquals(maxDoc, baseline.hits().length);

        List<Integer> collected = new ArrayList<>();
        FieldDoc after = null;
        int pageSize = 7;
        while (true) {
            Pagination.Page page = Pagination.searchAfter(searcher, new MatchAllDocsQuery(), sort, pageSize, after);
            for (FieldDoc fd : page.hits()) {
                collected.add(fd.doc());
            }
            if (page.hits().length < pageSize) {
                break;
            }
            after = page.hits()[page.hits().length - 1];
        }

        assertEquals(maxDoc, collected.size());
        for (int i = 0; i < maxDoc; i++) {
            assertEquals(Integer.valueOf(baseline.hits()[i].doc()), collected.get(i));
        }
        assertEquals((long) maxDoc, baseline.totalHits().value());
    }

    @Test
    public void sliceQueryPartitionsDocsWithoutOverlapOrGaps() throws Exception {
        int maxDoc = 17;
        IndexSearcher searcher = buildSearcher(maxDoc);
        int sliceCount = 3;
        List<Integer> allDocs = new ArrayList<>();
        for (int s = 0; s < sliceCount; s++) {
            SliceQuery sliceQuery = new SliceQuery(new MatchAllDocsQuery(), s, sliceCount);
            TopDocs topDocs = searcher.search(sliceQuery, maxDoc);
            for (ScoreDoc sd : topDocs.scoreDocs()) {
                allDocs.add(sd.doc);
            }
        }
        assertEquals(maxDoc, allDocs.size());
        Collections.sort(allDocs);
        for (int i = 0; i < maxDoc; i++) {
            assertEquals(Integer.valueOf(i), allDocs.get(i));
        }
    }
}
