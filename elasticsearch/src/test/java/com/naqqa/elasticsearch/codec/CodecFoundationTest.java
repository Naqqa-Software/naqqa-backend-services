package com.naqqa.elasticsearch.codec;

import com.naqqa.elasticsearch.store.ByteArrayDataInput;
import com.naqqa.elasticsearch.store.BytesDataOutput;
import com.naqqa.elasticsearch.test.Test;

import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class CodecFoundationTest {

    @Test
    public void smallFloatMonotonicAndRoundTripsSmallValues() {
        for (long i = 0; i < 16; i++) {
            assertEquals(i, SmallFloat.byte4ToInt(SmallFloat.intToByte4(i)));
        }
        long prevDecoded = -1;
        int prevByte = -1;
        Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            long value = (long) (Math.pow(2, random.nextDouble() * 40));
            byte b = SmallFloat.intToByte4(value);
            long decoded = SmallFloat.byte4ToInt(b);
            assertTrue(decoded <= value * 1.2 + 16, "decoded should approximate original");
        }
        for (int v = 0; v < 256; v++) {
            long d = SmallFloat.byte4ToInt((byte) v);
            assertTrue(d >= prevDecoded, "decoding must be monotonic in byte value");
            prevDecoded = d;
        }
    }

    @Test
    public void numericUtilsSortableOrderMatchesNaturalOrder() {
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            long a = random.nextLong();
            long b = random.nextLong();
            byte[] ba = new byte[8];
            byte[] bb = new byte[8];
            NumericUtils.longToSortableBytes(a, ba, 0);
            NumericUtils.longToSortableBytes(b, bb, 0);
            int cmp = NumericUtils.compareUnsigned(ba, 0, bb, 0, 8);
            assertEquals(Long.signum((long) Long.compare(a, b)), Long.signum((long) Integer.signum(cmp)));
            assertEquals(a, NumericUtils.sortableBytesToLong(ba, 0));
        }
        double x = -3.5;
        double y = 2.25;
        assertTrue(NumericUtils.doubleToSortableLong(x) < NumericUtils.doubleToSortableLong(y));
        assertEquals(x, NumericUtils.sortableLongToDouble(NumericUtils.doubleToSortableLong(x)), 0.0);
    }

    @Test
    public void forUtilRoundTripsBlocksWithExceptions() throws Exception {
        Random random = new Random(11);
        for (int trial = 0; trial < 20; trial++) {
            int[] values = new int[ForUtil.BLOCK_SIZE];
            for (int i = 0; i < values.length; i++) {
                values[i] = random.nextInt(20);
            }
            values[5] = 1_000_000 + trial;
            values[100] = 2_000_000;
            BytesDataOutput out = new BytesDataOutput();
            ForUtil.encodeBlock(values, out);
            ByteArrayDataInput in = out.toDataInput();
            int[] decoded = new int[ForUtil.BLOCK_SIZE];
            ForUtil.decodeBlock(in, decoded);
            for (int i = 0; i < values.length; i++) {
                assertEquals(values[i], decoded[i]);
            }
        }
    }

    @Test
    public void directWriterReaderRoundTripsAllBitWidths() throws Exception {
        Random random = new Random(3);
        for (int bits = 1; bits <= 64; bits++) {
            int count = 50;
            long mask = bits == 64 ? -1L : (1L << bits) - 1L;
            long[] values = new long[count];
            BytesDataOutput out = new BytesDataOutput();
            DirectWriter writer = new DirectWriter(out, bits);
            for (int i = 0; i < count; i++) {
                values[i] = random.nextLong() & mask;
                writer.add(values[i]);
            }
            writer.finish();
            byte[] bytes = out.toArrayCopy();
            com.naqqa.elasticsearch.store.RandomAccessInput rai = new com.naqqa.elasticsearch.store.RandomAccessInput() {
                @Override
                public long length() {
                    return bytes.length;
                }

                @Override
                public byte readByte(long pos) {
                    return bytes[(int) pos];
                }

                @Override
                public void readBytes(long pos, byte[] b, int off, int len) {
                    System.arraycopy(bytes, (int) pos, b, off, len);
                }

                @Override
                public short readShort(long pos) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int readInt(long pos) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public long readLong(long pos) {
                    throw new UnsupportedOperationException();
                }
            };
            DirectReader reader = new DirectReader(rai, 0, bits);
            for (int i = 0; i < count; i++) {
                assertEquals(values[i], reader.get(i));
            }
        }
    }
}
