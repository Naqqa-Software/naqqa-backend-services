package com.naqqa.elasticsearch.search.vectors;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

public final class PanamaVectorUtilSupport implements VectorUtilSupport {

    private static final VectorSpecies<Float> FS = FloatVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Integer> IS = IntVector.SPECIES_PREFERRED;
    private static final int INT_LANES = IS.length();
    private static final int WIDE_LANES = Math.max(8, INT_LANES);
    private static final VectorSpecies<Byte> BS = VectorSpecies.of(byte.class, VectorShape.forBitSize(WIDE_LANES * 8));
    private static final VectorSpecies<Short> SS = VectorSpecies.of(short.class, VectorShape.forBitSize(WIDE_LANES * 16));
    private static final int INT_PARTS = WIDE_LANES / INT_LANES;
    private static final VectorSpecies<Long> LS = LongVector.SPECIES_PREFERRED;
    private static final VectorSpecies<Byte> LBS = VectorSpecies.of(byte.class, LS.vectorShape());

    public PanamaVectorUtilSupport() {
        if (FS.vectorBitSize() < 128 || IS.vectorBitSize() < 128) {
            throw new IllegalStateException("preferred vector size too small: " + FS.vectorBitSize());
        }
        if (WIDE_LANES * 16 > VectorShape.preferredShape().vectorBitSize()) {
            throw new IllegalStateException("unsupported vector shape");
        }
        float[] probe = new float[FS.length() * 2 + 3];
        for (int i = 0; i < probe.length; i++) {
            probe[i] = i;
        }
        dotProduct(probe, probe);
        byte[] bytes = new byte[BS.length() * 2 + 3];
        dotProduct(bytes, bytes);
        xorBitCount(bytes, bytes);
    }

    @Override
    public String name() {
        return "panama(float=" + FS.vectorBitSize() + "bit,int=" + IS.vectorBitSize() + "bit)";
    }

    @Override
    public float dotProduct(float[] a, float[] b) {
        int n = a.length;
        int i = 0;
        float res = 0;
        int step = FS.length();
        int bound = FS.loopBound(n);
        if (bound > 0) {
            FloatVector acc1 = FloatVector.zero(FS);
            FloatVector acc2 = FloatVector.zero(FS);
            int unrolled = bound - step;
            for (; i < unrolled; i += 2 * step) {
                acc1 = FloatVector.fromArray(FS, a, i).fma(FloatVector.fromArray(FS, b, i), acc1);
                acc2 = FloatVector.fromArray(FS, a, i + step).fma(FloatVector.fromArray(FS, b, i + step), acc2);
            }
            for (; i < bound; i += step) {
                acc1 = FloatVector.fromArray(FS, a, i).fma(FloatVector.fromArray(FS, b, i), acc1);
            }
            res = acc1.add(acc2).reduceLanes(VectorOperators.ADD);
        }
        for (; i < n; i++) {
            res += a[i] * b[i];
        }
        return res;
    }

    @Override
    public float cosine(float[] a, float[] b) {
        int n = a.length;
        int i = 0;
        double dot = 0;
        double n1 = 0;
        double n2 = 0;
        int bound = FS.loopBound(n);
        if (bound > 0) {
            FloatVector accDot = FloatVector.zero(FS);
            FloatVector accA = FloatVector.zero(FS);
            FloatVector accB = FloatVector.zero(FS);
            for (; i < bound; i += FS.length()) {
                FloatVector va = FloatVector.fromArray(FS, a, i);
                FloatVector vb = FloatVector.fromArray(FS, b, i);
                accDot = va.fma(vb, accDot);
                accA = va.fma(va, accA);
                accB = vb.fma(vb, accB);
            }
            dot = accDot.reduceLanes(VectorOperators.ADD);
            n1 = accA.reduceLanes(VectorOperators.ADD);
            n2 = accB.reduceLanes(VectorOperators.ADD);
        }
        for (; i < n; i++) {
            float x = a[i];
            float y = b[i];
            dot += x * y;
            n1 += x * x;
            n2 += y * y;
        }
        return (float) (dot / Math.sqrt(n1 * n2));
    }

    @Override
    public float squareDistance(float[] a, float[] b) {
        int n = a.length;
        int i = 0;
        float res = 0;
        int bound = FS.loopBound(n);
        if (bound > 0) {
            FloatVector acc = FloatVector.zero(FS);
            for (; i < bound; i += FS.length()) {
                FloatVector diff = FloatVector.fromArray(FS, a, i).sub(FloatVector.fromArray(FS, b, i));
                acc = diff.fma(diff, acc);
            }
            res = acc.reduceLanes(VectorOperators.ADD);
        }
        for (; i < n; i++) {
            float d = a[i] - b[i];
            res += d * d;
        }
        return res;
    }

    @Override
    public float l1Distance(float[] a, float[] b) {
        int n = a.length;
        int i = 0;
        float res = 0;
        int bound = FS.loopBound(n);
        if (bound > 0) {
            FloatVector acc = FloatVector.zero(FS);
            for (; i < bound; i += FS.length()) {
                acc = acc.add(FloatVector.fromArray(FS, a, i).sub(FloatVector.fromArray(FS, b, i)).abs());
            }
            res = acc.reduceLanes(VectorOperators.ADD);
        }
        for (; i < n; i++) {
            res += Math.abs(a[i] - b[i]);
        }
        return res;
    }

    private static ShortVector widen(ByteVector v) {
        return (ShortVector) v.convertShape(VectorOperators.B2S, SS, 0);
    }

    private static IntVector addWidened(IntVector acc, ShortVector v) {
        for (int p = 0; p < INT_PARTS; p++) {
            acc = acc.add((IntVector) v.convertShape(VectorOperators.S2I, IS, p));
        }
        return acc;
    }

    private static IntVector addSquares(IntVector acc, ShortVector v) {
        for (int p = 0; p < INT_PARTS; p++) {
            IntVector wide = (IntVector) v.convertShape(VectorOperators.S2I, IS, p);
            acc = acc.add(wide.mul(wide));
        }
        return acc;
    }

    @Override
    public int dotProduct(byte[] a, byte[] b) {
        int n = a.length;
        int i = 0;
        int res = 0;
        int bound = BS.loopBound(n);
        if (bound > 0) {
            IntVector acc = IntVector.zero(IS);
            for (; i < bound; i += BS.length()) {
                ShortVector sa = widen(ByteVector.fromArray(BS, a, i));
                ShortVector sb = widen(ByteVector.fromArray(BS, b, i));
                acc = addWidened(acc, sa.mul(sb));
            }
            res = acc.reduceLanes(VectorOperators.ADD);
        }
        for (; i < n; i++) {
            res += a[i] * b[i];
        }
        return res;
    }

    @Override
    public float cosine(byte[] a, byte[] b) {
        int n = a.length;
        int i = 0;
        int dot = 0;
        int n1 = 0;
        int n2 = 0;
        int bound = BS.loopBound(n);
        if (bound > 0) {
            IntVector accDot = IntVector.zero(IS);
            IntVector accA = IntVector.zero(IS);
            IntVector accB = IntVector.zero(IS);
            for (; i < bound; i += BS.length()) {
                ShortVector sa = widen(ByteVector.fromArray(BS, a, i));
                ShortVector sb = widen(ByteVector.fromArray(BS, b, i));
                accDot = addWidened(accDot, sa.mul(sb));
                accA = addWidened(accA, sa.mul(sa));
                accB = addWidened(accB, sb.mul(sb));
            }
            dot = accDot.reduceLanes(VectorOperators.ADD);
            n1 = accA.reduceLanes(VectorOperators.ADD);
            n2 = accB.reduceLanes(VectorOperators.ADD);
        }
        for (; i < n; i++) {
            int x = a[i];
            int y = b[i];
            dot += x * y;
            n1 += x * x;
            n2 += y * y;
        }
        return (float) (dot / Math.sqrt((double) n1 * (double) n2));
    }

    @Override
    public int squareDistance(byte[] a, byte[] b) {
        int n = a.length;
        int i = 0;
        int res = 0;
        int bound = BS.loopBound(n);
        if (bound > 0) {
            IntVector acc = IntVector.zero(IS);
            for (; i < bound; i += BS.length()) {
                ShortVector diff = widen(ByteVector.fromArray(BS, a, i)).sub(widen(ByteVector.fromArray(BS, b, i)));
                acc = addSquares(acc, diff);
            }
            res = acc.reduceLanes(VectorOperators.ADD);
        }
        for (; i < n; i++) {
            int d = a[i] - b[i];
            res += d * d;
        }
        return res;
    }

    @Override
    public int l1Distance(byte[] a, byte[] b) {
        int n = a.length;
        int i = 0;
        int res = 0;
        int bound = BS.loopBound(n);
        if (bound > 0) {
            IntVector acc = IntVector.zero(IS);
            for (; i < bound; i += BS.length()) {
                ShortVector diff = widen(ByteVector.fromArray(BS, a, i)).sub(widen(ByteVector.fromArray(BS, b, i)));
                acc = addWidened(acc, diff.abs());
            }
            res = acc.reduceLanes(VectorOperators.ADD);
        }
        for (; i < n; i++) {
            res += Math.abs(a[i] - b[i]);
        }
        return res;
    }

    @Override
    public int int4DotProductPacked(byte[] unpacked, byte[] packed) {
        int half = packed.length;
        int i = 0;
        int res = 0;
        int bound = BS.loopBound(half);
        if (bound > 0) {
            IntVector acc = IntVector.zero(IS);
            for (; i < bound; i += BS.length()) {
                ByteVector p = ByteVector.fromArray(BS, packed, i);
                ShortVector hi = widen(p.lanewise(VectorOperators.LSHR, 4));
                ShortVector lo = widen(p.and((byte) 0x0F));
                ShortVector q1 = widen(ByteVector.fromArray(BS, unpacked, i));
                ShortVector q2 = widen(ByteVector.fromArray(BS, unpacked, half + i));
                acc = addWidened(acc, hi.mul(q1).add(lo.mul(q2)));
            }
            res = acc.reduceLanes(VectorOperators.ADD);
        }
        for (; i < half; i++) {
            int p = packed[i] & 0xFF;
            res += (p >>> 4) * unpacked[i] + (p & 0x0F) * unpacked[half + i];
        }
        return res;
    }

    @Override
    public int int4SquareDistancePacked(byte[] unpacked, byte[] packed) {
        int half = packed.length;
        int i = 0;
        int res = 0;
        int bound = BS.loopBound(half);
        if (bound > 0) {
            IntVector acc = IntVector.zero(IS);
            for (; i < bound; i += BS.length()) {
                ByteVector p = ByteVector.fromArray(BS, packed, i);
                ShortVector d1 = widen(p.lanewise(VectorOperators.LSHR, 4)).sub(widen(ByteVector.fromArray(BS, unpacked, i)));
                ShortVector d2 = widen(p.and((byte) 0x0F)).sub(widen(ByteVector.fromArray(BS, unpacked, half + i)));
                acc = addWidened(acc, d1.mul(d1).add(d2.mul(d2)));
            }
            res = acc.reduceLanes(VectorOperators.ADD);
        }
        for (; i < half; i++) {
            int p = packed[i] & 0xFF;
            int d1 = (p >>> 4) - unpacked[i];
            int d2 = (p & 0x0F) - unpacked[half + i];
            res += d1 * d1 + d2 * d2;
        }
        return res;
    }

    @Override
    public long xorBitCount(byte[] a, byte[] b) {
        int n = a.length;
        int i = 0;
        long res = 0;
        int bound = LBS.loopBound(n);
        if (bound > 0) {
            LongVector acc = LongVector.zero(LS);
            for (; i < bound; i += LBS.length()) {
                ByteVector x = ByteVector.fromArray(LBS, a, i).lanewise(VectorOperators.XOR, ByteVector.fromArray(LBS, b, i));
                acc = acc.add(x.reinterpretAsLongs().lanewise(VectorOperators.BIT_COUNT));
            }
            res = acc.reduceLanes(VectorOperators.ADD);
        }
        if (i < n) {
            res += ScalarVectorUtilSupport.xorBitCount(a, i, b, i, n - i);
        }
        return res;
    }

    @Override
    public long int4BitDotProduct(byte[] query, byte[] binary) {
        int n = binary.length;
        int bound = LBS.loopBound(n);
        long res = 0;
        for (int plane = 0; plane < 4; plane++) {
            int offset = plane * n;
            int i = 0;
            long sub = 0;
            if (bound > 0) {
                LongVector acc = LongVector.zero(LS);
                for (; i < bound; i += LBS.length()) {
                    ByteVector x = ByteVector.fromArray(LBS, query, offset + i).and(ByteVector.fromArray(LBS, binary, i));
                    acc = acc.add(x.reinterpretAsLongs().lanewise(VectorOperators.BIT_COUNT));
                }
                sub = acc.reduceLanes(VectorOperators.ADD);
            }
            for (; i < n; i++) {
                sub += Integer.bitCount((query[offset + i] & binary[i]) & 0xFF);
            }
            res += sub << plane;
        }
        return res;
    }
}
