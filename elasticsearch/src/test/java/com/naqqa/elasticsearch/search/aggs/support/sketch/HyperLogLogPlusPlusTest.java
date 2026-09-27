package com.naqqa.elasticsearch.search.aggs.support.sketch;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

import com.naqqa.elasticsearch.test.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

public class HyperLogLogPlusPlusTest {

    private static void assertWithinSigmas(long actual, long expected, int precision, double sigmas, String msg) {
        double err = Math.abs((double) actual - expected) / expected;
        double bound = sigmas * HyperLogLogPlusPlus.standardError(precision);
        assertTrue(err < bound, msg + " expected=" + expected + " actual=" + actual + " err=" + err + " bound=" + bound);
    }

    @Test
    public void murmurKnownVectors() {
        MurmurHash3.Hash128 h = MurmurHash3.hash128("foo".getBytes(StandardCharsets.UTF_8), 0);
        assertEquals(-2129773440516405919L, h.h1());
        assertEquals(9128664383759220103L, h.h2());
        MurmurHash3.Hash128 empty = MurmurHash3.hash128(new byte[0], 0);
        assertEquals(0L, empty.h1());
        assertEquals(0L, empty.h2());
    }

    @Test
    public void murmurLongMatchesBytes() {
        SplittableRandom r = new SplittableRandom(3);
        for (int i = 0; i < 1000; i++) {
            long v = r.nextLong();
            byte[] b = new byte[8];
            for (int k = 0; k < 8; k++) {
                b[k] = (byte) (v >>> (8 * k));
            }
            assertEquals(MurmurHash3.hash64(b), MurmurHash3.hash64(v));
        }
        byte[] data = new byte[40];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 7 + 1);
        }
        for (int len = 0; len <= 33; len++) {
            byte[] slice = new byte[len];
            System.arraycopy(data, 3, slice, 0, len);
            assertEquals(MurmurHash3.hash64(slice), MurmurHash3.hash64(data, 3, len));
        }
        assertEquals(MurmurHash3.hash64("héllo".getBytes(StandardCharsets.UTF_8)), MurmurHash3.hash64("héllo"));
        assertEquals(MurmurHash3.hash64(Double.doubleToLongBits(1.5)), MurmurHash3.hash64(1.5));
    }

    @Test
    public void precisionFromThresholdLikeElasticsearch() {
        assertEquals(14L, HyperLogLogPlusPlus.precisionFromThreshold(3000));
        assertEquals(4L, HyperLogLogPlusPlus.precisionFromThreshold(0));
        assertEquals(4L, HyperLogLogPlusPlus.precisionFromThreshold(1));
        assertEquals(10L, HyperLogLogPlusPlus.precisionFromThreshold(100));
        assertEquals(18L, HyperLogLogPlusPlus.precisionFromThreshold(40000));
        assertEquals(18L, HyperLogLogPlusPlus.precisionFromThreshold(Long.MAX_VALUE / 16));
        assertEquals(8192L, HyperLogLogPlusPlus.thresholdFromPrecision(14));
        assertEquals(8L, HyperLogLogPlusPlus.thresholdFromPrecision(4));
        assertEquals(10L, HyperLogLogPlusPlus.linearCountingCutoff(4));
        assertEquals(350000L, HyperLogLogPlusPlus.linearCountingCutoff(18));
        assertThrows(IllegalArgumentException.class, () -> HyperLogLogPlusPlus.thresholdFromPrecision(3));
        assertThrows(IllegalArgumentException.class, () -> new HyperLogLogPlusPlus(19));
        assertThrows(IllegalArgumentException.class, () -> HyperLogLogPlusPlus.precisionFromThreshold(-1));
    }

    @Test
    public void encodeDecodeConsistentWithDirectRegisters() {
        SplittableRandom r = new SplittableRandom(11);
        for (int p = 4; p <= 18; p++) {
            for (int i = 0; i < 20000; i++) {
                long hash = r.nextLong();
                if (i % 3 == 0) {
                    hash &= ~(-1L >>> 20);
                }
                if (i % 7 == 0) {
                    hash = hash & (-1L << (64 - p));
                }
                int encoded = HyperLogLogPlusPlus.encodeHash(hash, p);
                assertTrue(encoded != 0);
                int index = (int) (hash >>> (64 - p));
                int runLen = Math.min(Long.numberOfLeadingZeros(hash << p), 64 - p) + 1;
                assertEquals((long) index, (long) HyperLogLogPlusPlus.decodeIndex(encoded, p));
                assertEquals((long) runLen, (long) HyperLogLogPlusPlus.decodeRunLen(encoded, p));
            }
        }
    }

    @Test
    public void smallCardinalityIsExactInLinearCounting() {
        HyperLogLogPlusPlus hll = new HyperLogLogPlusPlus(14);
        for (int i = 0; i < 1000; i++) {
            hll.collectLong(0, i);
            hll.collectLong(0, i);
        }
        assertTrue(hll.isLinearCounting(0));
        long c = hll.cardinality(0);
        assertTrue(c >= 999 && c <= 1000, "cardinality " + c);
        assertEquals(0L, hll.cardinality(5));
        HyperLogLogPlusPlus tiny = new HyperLogLogPlusPlus(4);
        tiny.collectString(0, "a");
        tiny.collectString(0, "b");
        tiny.collectString(0, "a");
        assertEquals(2L, tiny.cardinality(0));
        assertTrue(tiny.isLinearCounting(0));
        for (int i = 0; i < 100; i++) {
            tiny.collectLong(0, i);
        }
        assertFalse(tiny.isLinearCounting(0));
    }

    @Test
    public void accuracyAcrossRanges() {
        int[] precisions = {4, 8, 10, 12, 14, 16, 18};
        int[] cardinalities = {5, 50, 500, 3000, 12000, 50000};
        long seed = 1;
        for (int p : precisions) {
            for (int n : cardinalities) {
                HyperLogLogPlusPlus hll = new HyperLogLogPlusPlus(p);
                SplittableRandom r = new SplittableRandom(seed++);
                for (int i = 0; i < n; i++) {
                    hll.collect(0, r.nextLong());
                }
                long c = hll.cardinality(0);
                double bound = Math.max(4 * HyperLogLogPlusPlus.standardError(p), 0.01);
                double err = Math.abs((double) c - n) / n;
                assertTrue(err <= bound + 1.0 / n, "p=" + p + " n=" + n + " c=" + c);
            }
        }
    }

    @Test
    public void accuracyHundredThousand() {
        for (int p : new int[] {10, 14, 18}) {
            HyperLogLogPlusPlus hll = new HyperLogLogPlusPlus(p);
            for (long i = 0; i < 100_000; i++) {
                hll.collectLong(0, i);
            }
            assertFalse(hll.isLinearCounting(0));
            assertWithinSigmas(hll.cardinality(0), 100_000, p, 4, "p=" + p);
        }
    }

    @Test
    public void accuracyMillion() {
        HyperLogLog hll = new HyperLogLog(14);
        for (long i = 0; i < 1_000_000; i++) {
            hll.addLong(i * 31 + 7);
        }
        for (long i = 0; i < 100_000; i++) {
            hll.addLong(i * 31 + 7);
        }
        assertWithinSigmas(hll.cardinality(), 1_000_000, 14, 4, "1e6");
        HyperLogLog strings = new HyperLogLog(16);
        for (int i = 0; i < 1_000_000; i++) {
            strings.addString("value-" + i);
        }
        assertWithinSigmas(strings.cardinality(), 1_000_000, 16, 4, "strings");
    }

    @Test
    public void multipleBuckets() {
        HyperLogLogPlusPlus hll = new HyperLogLogPlusPlus(12, 2);
        int[] sizes = {0, 10, 100, 700, 5000, 30000};
        for (int b = 0; b < sizes.length; b++) {
            for (int i = 0; i < sizes[b]; i++) {
                hll.collectLong(b, (long) b * 1_000_000 + i);
            }
        }
        assertTrue(hll.maxOrd() >= sizes.length);
        for (int b = 0; b < sizes.length; b++) {
            long c = hll.cardinality(b);
            if (sizes[b] <= 700) {
                assertTrue(Math.abs(c - sizes[b]) <= 1, "bucket " + b + " c=" + c);
                assertTrue(hll.isLinearCounting(b));
            } else {
                assertWithinSigmas(c, sizes[b], 12, 4, "bucket " + b);
            }
        }
        hll.reset(5);
        assertEquals(0L, hll.cardinality(5));
    }

    @Test
    public void mergeShardsMatchesSingleSketch() {
        int[] totals = {50, 2000, 3500, 200_000};
        for (int total : totals) {
            int p = 14;
            HyperLogLogPlusPlus single = new HyperLogLogPlusPlus(p);
            HyperLogLogPlusPlus[] shards = new HyperLogLogPlusPlus[5];
            for (int s = 0; s < shards.length; s++) {
                shards[s] = new HyperLogLogPlusPlus(p);
            }
            SplittableRandom r = new SplittableRandom(total);
            for (int i = 0; i < total; i++) {
                long v = r.nextInt(total);
                single.collectLong(0, v);
                shards[r.nextInt(shards.length)].collectLong(3, v);
            }
            HyperLogLogPlusPlus merged = new HyperLogLogPlusPlus(p);
            for (HyperLogLogPlusPlus shard : shards) {
                merged.merge(7, shard, 3);
            }
            assertEquals(single.cardinality(0), merged.cardinality(7));
            assertTrue(single.equals(0, merged, 7), "state equality for total " + total);
        }
    }

    @Test
    public void mergeLinearIntoHllAndHllIntoLinear() {
        HyperLogLogPlusPlus big = new HyperLogLogPlusPlus(10);
        HyperLogLogPlusPlus small = new HyperLogLogPlusPlus(10);
        HyperLogLogPlusPlus all = new HyperLogLogPlusPlus(10);
        for (int i = 0; i < 10000; i++) {
            big.collectLong(0, i);
            all.collectLong(0, i);
        }
        for (int i = 20000; i < 20050; i++) {
            small.collectLong(0, i);
            all.collectLong(0, i);
        }
        assertFalse(big.isLinearCounting(0));
        assertTrue(small.isLinearCounting(0));
        HyperLogLogPlusPlus a = new HyperLogLogPlusPlus(10);
        a.merge(0, big, 0);
        a.merge(0, small, 0);
        HyperLogLogPlusPlus b = new HyperLogLogPlusPlus(10);
        b.merge(0, small, 0);
        b.merge(0, big, 0);
        assertTrue(a.equals(0, all, 0));
        assertTrue(b.equals(0, all, 0));
        assertEquals(all.cardinality(0), a.cardinality(0));
    }

    @Test
    public void mergeDifferentPrecisionThrows() {
        HyperLogLogPlusPlus a = new HyperLogLogPlusPlus(10);
        HyperLogLogPlusPlus b = new HyperLogLogPlusPlus(11);
        b.collectLong(0, 1);
        assertThrows(IllegalArgumentException.class, () -> a.merge(0, b, 0));
        HyperLogLog x = new HyperLogLog(12);
        HyperLogLog y = new HyperLogLog(13);
        assertThrows(IllegalArgumentException.class, () -> x.merge(y));
    }

    @Test
    public void serializationRoundTrip() throws Exception {
        for (int n : new int[] {0, 1, 100, 5000, 100000}) {
            HyperLogLogPlusPlus hll = new HyperLogLogPlusPlus(13, 3);
            for (int i = 0; i < n; i++) {
                hll.collectLong(2, i);
            }
            byte[] bytes = hll.toBytes(2);
            HyperLogLogPlusPlus copy = HyperLogLogPlusPlus.fromBytes(bytes);
            assertEquals(13L, copy.precision());
            assertEquals(hll.cardinality(2), copy.cardinality(0));
            assertTrue(hll.equals(2, copy, 0));
            assertEquals(hll.isLinearCounting(2), copy.isLinearCounting(0));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            hll.writeTo(2, new DataOutputStream(bos));
            HyperLogLogPlusPlus copy2 = HyperLogLogPlusPlus.readFrom(new DataInputStream(new ByteArrayInputStream(bos.toByteArray())));
            assertTrue(copy2.equals(0, hll, 2));
        }
        HyperLogLog facade = new HyperLogLog(14);
        for (int i = 0; i < 20000; i++) {
            facade.addDouble(i * 0.5);
        }
        HyperLogLog restored = HyperLogLog.fromBytes(facade.toBytes());
        assertTrue(restored.sameState(facade));
        assertEquals(facade.cardinality(), restored.cardinality());
        assertThrows(UncheckedIOException.class, () -> HyperLogLog.fromBytes(new byte[] {9, 9, 9}));
        assertThrows(UncheckedIOException.class, () -> HyperLogLog.fromBytes(new byte[] {1, 14, 1, 0}));
    }

    @Test
    public void facadeThresholdAndBytes() {
        HyperLogLog hll = HyperLogLog.withPrecisionThreshold(3000);
        assertEquals(14L, hll.precision());
        hll.addBytes(new byte[] {1, 2, 3});
        hll.addBytes(new byte[] {1, 2, 3});
        hll.addBytes(new byte[] {1, 2, 4});
        hll.addHash(42L);
        assertEquals(3L, hll.cardinality());
        assertTrue(hll.isLinearCounting());
    }
}
