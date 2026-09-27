package com.naqqa.elasticsearch.codec;

import com.naqqa.elasticsearch.test.Test;

import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertTrue;
import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class LZ4Test {

    @Test
    public void randomRoundTripFastAndHighCompression() {
        Random random = new Random(55);
        for (int trial = 0; trial < 30; trial++) {
            int len = random.nextInt(5000);
            byte[] src = new byte[len];
            if (random.nextBoolean()) {
                for (int i = 0; i < len; i++) {
                    src[i] = (byte) (random.nextInt(4));
                }
            } else {
                random.nextBytes(src);
            }
            byte[] fast = LZ4.compressFast(src);
            byte[] decodedFast = LZ4.decompress(fast, len);
            assertTrue(java.util.Arrays.equals(src, decodedFast), "fast round trip mismatch at trial " + trial);

            byte[] high = LZ4.compressHighCompression(src);
            byte[] decodedHigh = LZ4.decompress(high, len);
            assertTrue(java.util.Arrays.equals(src, decodedHigh), "high compression round trip mismatch at trial " + trial);
        }
    }

    @Test
    public void repetitiveDataCompressesSmaller() {
        byte[] src = new byte[10000];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) ('a' + (i % 5));
        }
        byte[] compressed = LZ4.compressHighCompression(src);
        assertTrue(compressed.length < src.length / 4, "expected strong compression, got " + compressed.length);
        assertEquals(src.length, LZ4.decompress(compressed, src.length).length);
    }
}
