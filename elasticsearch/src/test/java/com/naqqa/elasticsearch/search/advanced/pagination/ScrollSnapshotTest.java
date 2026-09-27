package com.naqqa.elasticsearch.search.advanced.pagination;

import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.SortField;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ScrollSnapshotTest {

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
    public void scrollSnapshotDoesNotSeeDocsAddedAfterOpening() throws Exception {
        IndexSearcher snapshotAt10 = buildSearcher(10);
        try (ScrollService scrollService = new ScrollService()) {
            Sort sort = new Sort(new SortField("num", SortField.Type.LONG));
            ScrollContext.ScrollPage first = scrollService.openFrom(snapshotAt10, new MatchAllDocsQuery(), sort, 4, 60_000L);
            String scrollId = first.scrollId();

            IndexSearcher biggerSnapshotOpenedLater = buildSearcher(20);
            assertEquals(20, biggerSnapshotOpenedLater.leafContexts().get(0).reader().maxDoc());

            List<Integer> collected = new ArrayList<>();
            for (var fd : first.hits()) {
                collected.add(fd.doc());
            }
            while (true) {
                ScrollContext.ScrollPage page = scrollService.next(scrollId);
                if (page.hits().length == 0) {
                    break;
                }
                for (var fd : page.hits()) {
                    collected.add(fd.doc());
                }
            }

            assertEquals(10, collected.size());
            for (int i = 0; i < 10; i++) {
                assertEquals(Integer.valueOf(i), collected.get(i));
            }
            assertTrue(scrollService.clear(scrollId));
        }
    }
}
