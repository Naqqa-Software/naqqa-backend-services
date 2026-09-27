package com.naqqa.elasticsearch.search.aggs.support.sketch;

import java.nio.charset.StandardCharsets;

public final class MurmurHash3 {

    public static final long DEFAULT_SEED = 0L;

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private MurmurHash3() {
    }

    public record Hash128(long h1, long h2) {
    }

    public static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    public static Hash128 hash128(byte[] key, int offset, int length, long seed) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (offset < 0 || length < 0 || offset + length > key.length) {
            throw new IndexOutOfBoundsException("offset=" + offset + ", length=" + length + ", size=" + key.length);
        }
        long h1 = seed;
        long h2 = seed;
        int nblocks = length >>> 4;
        for (int i = 0; i < nblocks; i++) {
            int base = offset + (i << 4);
            long k1 = getLongLE(key, base);
            long k2 = getLongLE(key, base + 8);
            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;
            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;
            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }
        int tail = offset + (nblocks << 4);
        int rem = length & 15;
        long k1 = 0;
        long k2 = 0;
        if (rem > 8) {
            for (int i = rem - 1; i >= 8; i--) {
                k2 ^= (key[tail + i] & 0xffL) << ((i - 8) * 8);
            }
            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;
        }
        if (rem > 0) {
            int top = Math.min(rem, 8);
            for (int i = top - 1; i >= 0; i--) {
                k1 ^= (key[tail + i] & 0xffL) << (i * 8);
            }
            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
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
        return new Hash128(h1, h2);
    }

    public static Hash128 hash128(byte[] key, long seed) {
        return hash128(key, 0, key.length, seed);
    }

    public static long hash64(byte[] key, int offset, int length) {
        return hash128(key, offset, length, DEFAULT_SEED).h1();
    }

    public static long hash64(byte[] key) {
        return hash128(key, 0, key.length, DEFAULT_SEED).h1();
    }

    public static long hash64(String value) {
        return hash64(value.getBytes(StandardCharsets.UTF_8));
    }

    public static long hash64(long value) {
        long h1 = DEFAULT_SEED;
        long h2 = DEFAULT_SEED;
        long k1 = value;
        k1 *= C1;
        k1 = Long.rotateLeft(k1, 31);
        k1 *= C2;
        h1 ^= k1;
        h1 ^= 8;
        h2 ^= 8;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        return h1;
    }

    public static long hash64(double value) {
        return hash64(Double.doubleToLongBits(value));
    }

    private static long getLongLE(byte[] b, int i) {
        return (b[i] & 0xffL)
            | (b[i + 1] & 0xffL) << 8
            | (b[i + 2] & 0xffL) << 16
            | (b[i + 3] & 0xffL) << 24
            | (b[i + 4] & 0xffL) << 32
            | (b[i + 5] & 0xffL) << 40
            | (b[i + 6] & 0xffL) << 48
            | (b[i + 7] & 0xffL) << 56;
    }
}
