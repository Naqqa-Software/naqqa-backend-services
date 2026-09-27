package com.naqqa.elasticsearch.codec.points;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;
import com.naqqa.elasticsearch.test.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class BKDTest {

    @Test
    public void intersectMatchesBruteForce2D() throws Exception {
        Random random = new Random(77);
        int n = 3000;
        int[][] pointsX = new int[n][2];
        byte[][] packed = new byte[n][8];
        for (int i = 0; i < n; i++) {
            int x = random.nextInt(1000);
            int y = random.nextInt(1000);
            pointsX[i][0] = x;
            pointsX[i][1] = y;
            NumericUtils.intToSortableBytes(x, packed[i], 0);
            NumericUtils.intToSortableBytes(y, packed[i], 4);
        }

        BKDWriter writer = new BKDWriter(2, 4, 64);
        for (int i = 0; i < n; i++) {
            writer.add(packed[i], i);
        }
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        try (IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT)) {
            writer.finish(out);
        }

        int qx0 = 200;
        int qx1 = 700;
        int qy0 = 100;
        int qy1 = 900;
        byte[] qMinX = new byte[4];
        byte[] qMaxX = new byte[4];
        byte[] qMinY = new byte[4];
        byte[] qMaxY = new byte[4];
        NumericUtils.intToSortableBytes(qx0, qMinX, 0);
        NumericUtils.intToSortableBytes(qx1, qMaxX, 0);
        NumericUtils.intToSortableBytes(qy0, qMinY, 0);
        NumericUtils.intToSortableBytes(qy1, qMaxY, 0);

        Set<Integer> expected = new TreeSet<>();
        for (int i = 0; i < n; i++) {
            if (pointsX[i][0] >= qx0 && pointsX[i][0] <= qx1 && pointsX[i][1] >= qy0 && pointsX[i][1] <= qy1) {
                expected.add(i);
            }
        }

        try (IndexInput in = dir.openInput("bkd", IOContext.DEFAULT)) {
            BKDReader reader = new BKDReader(in);
            assertEquals(n, reader.numPoints());

            byte[] gminx = reader.minPackedValue(0);
            byte[] gmaxx = reader.maxPackedValue(0);
            int actualMinX = NumericUtils.sortableBytesToInt(gminx, 0);
            int actualMaxX = NumericUtils.sortableBytesToInt(gmaxx, 0);
            int expectedMinX = Integer.MAX_VALUE;
            int expectedMaxX = Integer.MIN_VALUE;
            for (int[] p : pointsX) {
                expectedMinX = Math.min(expectedMinX, p[0]);
                expectedMaxX = Math.max(expectedMaxX, p[0]);
            }
            assertEquals(expectedMinX, actualMinX);
            assertEquals(expectedMaxX, actualMaxX);

            Set<Integer> got = new HashSet<>();
            IntersectVisitor visitor = new IntersectVisitor() {
                @Override
                public Relation compare(byte[][] cellMin, byte[][] cellMax) {
                    boolean outside = NumericUtils.compareUnsigned(cellMax[0], 0, qMinX, 0, 4) < 0
                        || NumericUtils.compareUnsigned(cellMin[0], 0, qMaxX, 0, 4) > 0
                        || NumericUtils.compareUnsigned(cellMax[1], 0, qMinY, 0, 4) < 0
                        || NumericUtils.compareUnsigned(cellMin[1], 0, qMaxY, 0, 4) > 0;
                    if (outside) {
                        return Relation.CELL_OUTSIDE_QUERY;
                    }
                    boolean inside = NumericUtils.compareUnsigned(cellMin[0], 0, qMinX, 0, 4) >= 0
                        && NumericUtils.compareUnsigned(cellMax[0], 0, qMaxX, 0, 4) <= 0
                        && NumericUtils.compareUnsigned(cellMin[1], 0, qMinY, 0, 4) >= 0
                        && NumericUtils.compareUnsigned(cellMax[1], 0, qMaxY, 0, 4) <= 0;
                    return inside ? Relation.CELL_INSIDE_QUERY : Relation.CELL_CROSSES_QUERY;
                }

                @Override
                public void visit(int docId) {
                    got.add(docId);
                }

                @Override
                public void visit(int docId, byte[] packedValue) {
                    int x = NumericUtils.sortableBytesToInt(packedValue, 0);
                    int y = NumericUtils.sortableBytesToInt(packedValue, 4);
                    if (x >= qx0 && x <= qx1 && y >= qy0 && y <= qy1) {
                        got.add(docId);
                    }
                }
            };
            reader.intersect(visitor);
            assertEquals(expected.size(), got.size());
            assertTrue(expected.equals(got), "intersect result differs from brute force");

            long estimate = reader.estimatePointCount(visitor);
            assertTrue(estimate >= expected.size(), "estimate should be an upper bound");
        }
    }
}
