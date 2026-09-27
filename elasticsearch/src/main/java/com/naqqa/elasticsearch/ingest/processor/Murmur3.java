package com.naqqa.elasticsearch.ingest.processor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class Murmur3 {

    private Murmur3() {
    }

    public static byte[] hash128(String input, long seed) {
        byte[] data = input.getBytes(StandardCharsets.UTF_8);
        long[] result = hash128(data, seed);
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(result[0]);
        buffer.putLong(result[1]);
        return buffer.array();
    }

    private static long[] hash128(byte[] key, long seed) {
        int length = key.length;
        long c1 = 0x87c37b91114253d5L;
        long c2 = 0x4cf5ad432745937fL;
        long h1 = seed;
        long h2 = seed;
        int roundedEnd = (length / 16) * 16;

        for (int i = 0; i < roundedEnd; i += 16) {
            long k1 = getLong(key, i);
            long k2 = getLong(key, i + 8);
            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;
            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        long k1 = 0;
        long k2 = 0;
        int remaining = length - roundedEnd;
        if (remaining > 8) {
            k2 = getPartialLong(key, roundedEnd + 8, remaining - 8);
            k2 *= c2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= c1;
            h2 ^= k2;
        }
        if (remaining > 0) {
            k1 = getPartialLong(key, roundedEnd, Math.min(remaining, 8));
            k1 *= c1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= c2;
            h1 ^= k1;
        }

        h1 ^= length;
        h2 ^= length;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        h2 += h1;
        return new long[] {h1, h2};
    }

    private static long getLong(byte[] data, int index) {
        return ByteBuffer.wrap(data, index, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static long getPartialLong(byte[] data, int index, int length) {
        long result = 0;
        for (int i = 0; i < length; i++) {
            result |= (data[index + i] & 0xFFL) << (8 * i);
        }
        return result;
    }

    private static long fmix64(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
