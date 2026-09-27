package com.naqqa.elasticsearch.codec;

import java.util.Arrays;

public final class LZ4 {

    private static final int MIN_MATCH = 4;
    private static final int HASH_BITS = 16;
    private static final int HASH_SIZE = 1 << HASH_BITS;
    private static final int MAX_DISTANCE = 65535;

    private LZ4() {
    }

    public static byte[] compressFast(byte[] src) {
        return compress(src, 1);
    }

    public static byte[] compressHighCompression(byte[] src) {
        return compress(src, 64);
    }

    private static int hash(byte[] src, int pos) {
        int h = (src[pos] & 0xFF) | ((src[pos + 1] & 0xFF) << 8) | ((src[pos + 2] & 0xFF) << 16) | ((src[pos + 3] & 0xFF) << 24);
        h *= 0x9E3779B1;
        return (h >>> (32 - HASH_BITS)) & (HASH_SIZE - 1);
    }

    private static int matchLength(byte[] src, int a, int b, int n) {
        int len = 0;
        while (b + len < n && src[a + len] == src[b + len]) {
            len++;
        }
        return len;
    }

    private static byte[] compress(byte[] src, int maxChainLen) {
        int n = src.length;
        byte[] out = new byte[Math.max(64, n + n / 2 + 32)];
        int[] outPos = {0};

        int[] hashTable = new int[HASH_SIZE];
        Arrays.fill(hashTable, -1);
        int[] chainTable = new int[Math.max(1, n)];

        int anchor = 0;
        int pos = 0;
        while (pos + MIN_MATCH <= n) {
            int h = hash(src, pos);
            int candidate = hashTable[h];
            chainTable[pos] = candidate;
            hashTable[h] = pos;

            int bestLen = 0;
            int bestOffset = 0;
            int cand = candidate;
            int chainLen = 0;
            while (cand >= 0 && pos - cand <= MAX_DISTANCE && chainLen < maxChainLen) {
                int len = matchLength(src, cand, pos, n);
                if (len > bestLen) {
                    bestLen = len;
                    bestOffset = pos - cand;
                }
                cand = chainTable[cand];
                chainLen++;
            }

            if (bestLen >= MIN_MATCH) {
                out = ensureCapacity(out, outPos[0], (pos - anchor) + 32);
                outPos[0] = writeSequence(out, outPos[0], src, anchor, pos, bestOffset, bestLen);
                pos += bestLen;
                anchor = pos;
            } else {
                pos++;
            }
        }
        out = ensureCapacity(out, outPos[0], (n - anchor) + 16);
        outPos[0] = writeFinalLiterals(out, outPos[0], src, anchor, n);
        return Arrays.copyOf(out, outPos[0]);
    }

    private static byte[] ensureCapacity(byte[] out, int used, int extra) {
        if (used + extra > out.length) {
            return Arrays.copyOf(out, Math.max(out.length * 2, used + extra));
        }
        return out;
    }

    private static int writeLengthExtra(byte[] out, int outPos, int value) {
        while (value >= 255) {
            out[outPos++] = (byte) 255;
            value -= 255;
        }
        out[outPos++] = (byte) value;
        return outPos;
    }

    private static int writeSequence(byte[] out, int outPos, byte[] src, int anchor, int pos, int offset, int matchLen) {
        int litLen = pos - anchor;
        int matchStored = matchLen - MIN_MATCH;
        int litNibble = Math.min(litLen, 15);
        int matchNibble = Math.min(matchStored, 15);
        out[outPos++] = (byte) ((litNibble << 4) | matchNibble);
        if (litLen >= 15) {
            outPos = writeLengthExtra(out, outPos, litLen - 15);
        }
        System.arraycopy(src, anchor, out, outPos, litLen);
        outPos += litLen;
        out[outPos++] = (byte) offset;
        out[outPos++] = (byte) (offset >>> 8);
        if (matchStored >= 15) {
            outPos = writeLengthExtra(out, outPos, matchStored - 15);
        }
        return outPos;
    }

    private static int writeFinalLiterals(byte[] out, int outPos, byte[] src, int anchor, int n) {
        int litLen = n - anchor;
        int litNibble = Math.min(litLen, 15);
        out[outPos++] = (byte) (litNibble << 4);
        if (litLen >= 15) {
            outPos = writeLengthExtra(out, outPos, litLen - 15);
        }
        System.arraycopy(src, anchor, out, outPos, litLen);
        outPos += litLen;
        return outPos;
    }

    public static byte[] decompress(byte[] compressed, int originalLength) {
        byte[] dst = new byte[originalLength];
        int inPos = 0;
        int outPos = 0;
        while (outPos < originalLength) {
            int token = compressed[inPos++] & 0xFF;
            int litLen = token >>> 4;
            if (litLen == 15) {
                int extra;
                do {
                    extra = compressed[inPos++] & 0xFF;
                    litLen += extra;
                } while (extra == 255);
            }
            System.arraycopy(compressed, inPos, dst, outPos, litLen);
            inPos += litLen;
            outPos += litLen;
            if (outPos >= originalLength) {
                break;
            }
            int matchLenNibble = token & 0x0F;
            int offset = (compressed[inPos] & 0xFF) | ((compressed[inPos + 1] & 0xFF) << 8);
            inPos += 2;
            int matchLen = matchLenNibble;
            if (matchLen == 15) {
                int extra;
                do {
                    extra = compressed[inPos++] & 0xFF;
                    matchLen += extra;
                } while (extra == 255);
            }
            matchLen += MIN_MATCH;
            int matchStart = outPos - offset;
            for (int i = 0; i < matchLen; i++) {
                dst[outPos + i] = dst[matchStart + i];
            }
            outPos += matchLen;
        }
        return dst;
    }
}
