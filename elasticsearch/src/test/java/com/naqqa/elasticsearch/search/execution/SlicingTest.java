package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class SlicingTest {

    private static LeafReaderContext ctx(int maxDoc, int ord) {
        LeafReader reader = SimpleLeafReader.builder(maxDoc).build();
        return new LeafReaderContext(reader, 0, ord);
    }

    @Test
    public void groupsSmallSegmentsBySegmentCount() {
        List<LeafReaderContext> leaves = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            leaves.add(ctx(10, i));
        }
        List<LeafSlice> slices = IndexSearcher.computeSlices(leaves, 250_000, 5);
        assertEquals(3, slices.size());
        assertEquals(5, slices.get(0).leaves().size());
        assertEquals(5, slices.get(1).leaves().size());
        assertEquals(2, slices.get(2).leaves().size());
    }

    @Test
    public void groupsByDocCountWhenSegmentsAreLarge() {
        List<LeafReaderContext> leaves = List.of(ctx(100_000, 0), ctx(100_000, 1), ctx(100_000, 2));
        List<LeafSlice> slices = IndexSearcher.computeSlices(leaves, 250_000, 5);
        assertEquals(2, slices.size());
        assertEquals(2, slices.get(0).leaves().size());
        assertEquals(1, slices.get(1).leaves().size());
        assertTrue(slices.get(0).totalMaxDoc() <= 250_000);
    }

    @Test
    public void oversizedSingleSegmentBecomesItsOwnSlice() {
        List<LeafReaderContext> leaves = List.of(ctx(500_000, 0), ctx(10, 1));
        List<LeafSlice> slices = IndexSearcher.computeSlices(leaves, 250_000, 5);
        assertEquals(2, slices.size());
        assertEquals(1, slices.get(0).leaves().size());
        assertEquals(500_000, slices.get(0).totalMaxDoc());
    }

    @Test
    public void allLeavesArePreservedAcrossSlices() {
        List<LeafReaderContext> leaves = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            leaves.add(ctx(37 + i, i));
        }
        List<LeafSlice> slices = IndexSearcher.computeSlices(leaves, 250_000, 5);
        int totalLeaves = 0;
        long totalDocs = 0;
        for (LeafSlice slice : slices) {
            totalLeaves += slice.leaves().size();
            totalDocs += slice.totalMaxDoc();
        }
        assertEquals(leaves.size(), totalLeaves);
        long expectedDocs = 0;
        for (LeafReaderContext c : leaves) {
            expectedDocs += c.reader().maxDoc();
        }
        assertEquals(expectedDocs, totalDocs);
    }

    @Test
    public void singleLeafProducesSingleSlice() {
        List<LeafReaderContext> leaves = List.of(ctx(42, 0));
        List<LeafSlice> slices = IndexSearcher.computeSlices(leaves, 250_000, 5);
        assertEquals(1, slices.size());
    }
}
