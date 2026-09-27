package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;

public final class BinaryDocValuesReader {

    private final int maxDoc;
    private final boolean dense;
    private final int[] presentDocs;
    private final byte[][] values;
    private int position = -1;

    public BinaryDocValuesReader(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        CodecUtil.checkHeader(in, BinaryDocValuesWriter.CODEC_NAME, BinaryDocValuesWriter.VERSION_START, BinaryDocValuesWriter.VERSION_CURRENT);
        this.maxDoc = in.readVInt();
        int presentCount = in.readVInt();
        this.dense = in.readByte() != 0;
        if (dense) {
            this.presentDocs = null;
        } else {
            this.presentDocs = new int[presentCount];
            int prev = -1;
            for (int i = 0; i < presentCount; i++) {
                prev = prev + in.readVInt() + 1;
                presentDocs[i] = prev;
            }
        }
        long[] lengths = NumericBlockCodec.readPacked(in, presentCount);
        this.values = new byte[presentCount][];
        for (int i = 0; i < presentCount; i++) {
            byte[] b = new byte[(int) lengths[i]];
            in.readBytes(b, 0, b.length);
            values[i] = b;
        }
    }

    public int maxDoc() {
        return maxDoc;
    }

    public boolean advanceExact(int target) {
        if (dense) {
            position = target;
            return target >= 0 && target < maxDoc;
        }
        int idx = java.util.Arrays.binarySearch(presentDocs, target);
        if (idx < 0) {
            position = -1;
            return false;
        }
        position = idx;
        return true;
    }

    public byte[] binaryValue() {
        if (position < 0) {
            throw new IllegalStateException("no current value; call advanceExact first");
        }
        return values[position];
    }
}
