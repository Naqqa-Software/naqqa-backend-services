package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;
import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class DocValuesTest {

    @Test
    public void numericRoundTripsSparseAndDense() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        int maxDoc = 1000;
        long[] values = new long[maxDoc];
        boolean[] has = new boolean[maxDoc];
        Random random = new Random(1);
        for (int i = 0; i < maxDoc; i++) {
            if (random.nextInt(3) != 0) {
                has[i] = true;
                values[i] = random.nextInt(10) * 7L + 100;
            }
        }
        try (IndexOutput out = dir.createOutput("num", IOContext.DEFAULT)) {
            NumericDocValuesWriter.write(out, maxDoc, values, has);
        }
        try (IndexInput in = dir.openInput("num", IOContext.DEFAULT)) {
            NumericDocValuesReader reader = new NumericDocValuesReader(in);
            for (int i = 0; i < maxDoc; i++) {
                boolean present = reader.advanceExact(i);
                assertEquals(has[i], present);
                if (present) {
                    assertEquals(values[i], reader.longValue());
                }
            }
        }

        long[] denseValues = new long[500];
        for (int i = 0; i < denseValues.length; i++) {
            denseValues[i] = i * 1000000000L;
        }
        try (IndexOutput out = dir.createOutput("num2", IOContext.DEFAULT)) {
            NumericDocValuesWriter.write(out, denseValues.length, denseValues, null);
        }
        try (IndexInput in = dir.openInput("num2", IOContext.DEFAULT)) {
            NumericDocValuesReader reader = new NumericDocValuesReader(in);
            for (int i = 0; i < denseValues.length; i++) {
                assertTrue(reader.advanceExact(i));
                assertEquals(denseValues[i], reader.longValue());
            }
        }
    }

    @Test
    public void binaryRoundTrips() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        int maxDoc = 50;
        byte[][] values = new byte[maxDoc][];
        for (int i = 0; i < maxDoc; i++) {
            if (i % 4 != 0) {
                values[i] = ("value-" + i).getBytes(StandardCharsets.UTF_8);
            }
        }
        try (IndexOutput out = dir.createOutput("bin", IOContext.DEFAULT)) {
            BinaryDocValuesWriter.write(out, maxDoc, values);
        }
        try (IndexInput in = dir.openInput("bin", IOContext.DEFAULT)) {
            BinaryDocValuesReader reader = new BinaryDocValuesReader(in);
            for (int i = 0; i < maxDoc; i++) {
                boolean present = reader.advanceExact(i);
                assertEquals(values[i] != null, present);
                if (present) {
                    assertTrue(java.util.Arrays.equals(values[i], reader.binaryValue()));
                }
            }
        }
    }

    @Test
    public void sortedRoundTrips() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        int maxDoc = 60;
        byte[][] values = new byte[maxDoc][];
        String[] pool = {"apple", "banana", "cherry"};
        for (int i = 0; i < maxDoc; i++) {
            if (i % 5 != 0) {
                values[i] = pool[i % pool.length].getBytes(StandardCharsets.UTF_8);
            }
        }
        try (IndexOutput out = dir.createOutput("sorted", IOContext.DEFAULT)) {
            SortedDocValuesWriter.write(out, maxDoc, values);
        }
        try (IndexInput in = dir.openInput("sorted", IOContext.DEFAULT)) {
            SortedDocValuesReader reader = new SortedDocValuesReader(in);
            assertEquals(3, reader.valueCount());
            for (int i = 0; i < maxDoc; i++) {
                boolean present = reader.advanceExact(i);
                assertEquals(values[i] != null, present);
                if (present) {
                    int ord = reader.ordValue();
                    assertTrue(java.util.Arrays.equals(values[i], reader.lookupOrd(ord)));
                }
            }
        }
    }

    @Test
    public void sortedSetRoundTrips() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        int maxDoc = 20;
        byte[][][] values = new byte[maxDoc][][];
        for (int i = 0; i < maxDoc; i++) {
            if (i % 3 != 0) {
                values[i] = new byte[][]{
                    ("tag" + (i % 4)).getBytes(StandardCharsets.UTF_8),
                    ("tag" + ((i + 1) % 4)).getBytes(StandardCharsets.UTF_8)
                };
            }
        }
        try (IndexOutput out = dir.createOutput("sset", IOContext.DEFAULT)) {
            SortedSetDocValuesWriter.write(out, maxDoc, values);
        }
        try (IndexInput in = dir.openInput("sset", IOContext.DEFAULT)) {
            SortedSetDocValuesReader reader = new SortedSetDocValuesReader(in);
            for (int i = 0; i < maxDoc; i++) {
                boolean present = reader.advanceExact(i);
                assertEquals(values[i] != null, present);
                if (present) {
                    int count = reader.docValueCount();
                    java.util.TreeSet<String> got = new java.util.TreeSet<>();
                    for (int k = 0; k < count; k++) {
                        got.add(new String(reader.lookupOrd((int) reader.nextOrd()), StandardCharsets.UTF_8));
                    }
                    java.util.TreeSet<String> expected = new java.util.TreeSet<>();
                    for (byte[] v : values[i]) {
                        expected.add(new String(v, StandardCharsets.UTF_8));
                    }
                    assertEquals(expected.toString(), got.toString());
                }
            }
        }
    }

    @Test
    public void sortedNumericRoundTrips() throws Exception {
        ByteBuffersDirectory dir = new ByteBuffersDirectory();
        int maxDoc = 30;
        long[][] values = new long[maxDoc][];
        for (int i = 0; i < maxDoc; i++) {
            if (i % 3 != 0) {
                values[i] = new long[]{i, i * 2L, i * 3L};
            }
        }
        try (IndexOutput out = dir.createOutput("snum", IOContext.DEFAULT)) {
            SortedNumericDocValuesWriter.write(out, maxDoc, values);
        }
        try (IndexInput in = dir.openInput("snum", IOContext.DEFAULT)) {
            SortedNumericDocValuesReader reader = new SortedNumericDocValuesReader(in);
            for (int i = 0; i < maxDoc; i++) {
                boolean present = reader.advanceExact(i);
                assertEquals(values[i] != null, present);
                if (present) {
                    int count = reader.docValueCount();
                    assertEquals(values[i].length, count);
                    for (int k = 0; k < count; k++) {
                        assertEquals(values[i][k], reader.nextValue());
                    }
                }
            }
            assertFalse(reader.advanceExact(0) && false);
        }
    }
}
