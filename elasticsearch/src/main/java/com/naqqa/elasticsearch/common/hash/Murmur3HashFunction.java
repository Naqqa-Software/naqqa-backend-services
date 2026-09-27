package com.naqqa.elasticsearch.common.hash;

import java.nio.charset.StandardCharsets;

public final class Murmur3HashFunction {

    private Murmur3HashFunction() {
    }

    public static int hash(String routingKey) {
        byte[] bytesToHash = new byte[routingKey.length() * 2];
        for (int i = 0; i < routingKey.length(); ++i) {
            char c = routingKey.charAt(i);
            byte b1 = (byte) c;
            byte b2 = (byte) (c >>> 8);
            bytesToHash[i * 2] = b1;
            bytesToHash[i * 2 + 1] = b2;
        }
        return hash32(bytesToHash, 0, bytesToHash.length, 0);
    }

    public static int hash32(byte[] data, int offset, int length, int seed) {
        int h1 = seed;
        int c1 = 0xcc9e2d51;
        int c2 = 0x1b873593;
        int roundedEnd = offset + (length & 0xfffffffc);

        for (int i = offset; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff) | ((data[i + 1] & 0xff) << 8) | ((data[i + 2] & 0xff) << 16) | (data[i + 3] << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        switch (length & 0x03) {
            case 3:
                k1 = (data[roundedEnd + 2] & 0xff) << 16;
            case 2:
                k1 |= (data[roundedEnd + 1] & 0xff) << 8;
            case 1:
                k1 |= (data[roundedEnd] & 0xff);
                k1 *= c1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= c2;
                h1 ^= k1;
        }

        h1 ^= length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;

        return h1;
    }

    public static long[] hash128(byte[] key, int offset, int length, long seed) {
        long h1 = seed;
        long h2 = seed;
        long c1 = 0x87c37b91114253d5L;
        long c2 = 0x4cf5ad432745937fL;

        int roundedEnd = offset + (length & ~15);
        for (int i = offset; i < roundedEnd; i += 16) {
            long k1 = getLongLE(key, i);
            long k2 = getLongLE(key, i + 8);
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
        int tail = length & 15;
        int tailStart = roundedEnd;
        switch (tail) {
            case 15:
                k2 ^= ((long) (key[tailStart + 14] & 0xff)) << 48;
            case 14:
                k2 ^= ((long) (key[tailStart + 13] & 0xff)) << 40;
            case 13:
                k2 ^= ((long) (key[tailStart + 12] & 0xff)) << 32;
            case 12:
                k2 ^= ((long) (key[tailStart + 11] & 0xff)) << 24;
            case 11:
                k2 ^= ((long) (key[tailStart + 10] & 0xff)) << 16;
            case 10:
                k2 ^= ((long) (key[tailStart + 9] & 0xff)) << 8;
            case 9:
                k2 ^= (key[tailStart + 8] & 0xff);
                k2 *= c2;
                k2 = Long.rotateLeft(k2, 33);
                k2 *= c1;
                h2 ^= k2;
            case 8:
                k1 ^= ((long) (key[tailStart + 7] & 0xff)) << 56;
            case 7:
                k1 ^= ((long) (key[tailStart + 6] & 0xff)) << 48;
            case 6:
                k1 ^= ((long) (key[tailStart + 5] & 0xff)) << 40;
            case 5:
                k1 ^= ((long) (key[tailStart + 4] & 0xff)) << 32;
            case 4:
                k1 ^= ((long) (key[tailStart + 3] & 0xff)) << 24;
            case 3:
                k1 ^= ((long) (key[tailStart + 2] & 0xff)) << 16;
            case 2:
                k1 ^= ((long) (key[tailStart + 1] & 0xff)) << 8;
            case 1:
                k1 ^= (key[tailStart] & 0xff);
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
        return new long[] { h1, h2 };
    }

    private static long getLongLE(byte[] data, int index) {
        return (data[index] & 0xFFL) | ((data[index + 1] & 0xFFL) << 8) | ((data[index + 2] & 0xFFL) << 16)
            | ((data[index + 3] & 0xFFL) << 24) | ((data[index + 4] & 0xFFL) << 32) | ((data[index + 5] & 0xFFL) << 40)
            | ((data[index + 6] & 0xFFL) << 48) | ((data[index + 7] & 0xFFL) << 56);
    }

    private static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    public static long[] hash128(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        return hash128(bytes, 0, bytes.length, 0);
    }
}
