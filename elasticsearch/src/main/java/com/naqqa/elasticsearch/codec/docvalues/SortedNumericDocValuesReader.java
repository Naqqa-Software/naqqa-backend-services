package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;

public final class SortedNumericDocValuesReader {

    private final int maxDoc;
    private final boolean dense;
    private final int[] presentDocs;
    private final long[] counts;
    private final long[] flatValues;
    private final int[] flatOffsets;
    private int position = -1;
    private int cursor;
    private int cursorEnd;

    public SortedNumericDocValuesReader(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        CodecUtil.checkHeader(in, SortedNumericDocValuesWriter.CODEC_NAME, SortedNumericDocValuesWriter.VERSION_START, SortedNumericDocValuesWriter.VERSION_CURRENT);
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
        this.counts = NumericBlockCodec.readPacked(in, presentCount);
        this.flatOffsets = new int[presentCount + 1];
        int total = 0;
        for (int i = 0; i < presentCount; i++) {
            flatOffsets[i] = total;
            total += (int) counts[i];
        }
        flatOffsets[presentCount] = total;
        this.flatValues = NumericBlockCodec.readPacked(in, total);
    }

    public boolean advanceExact(int target) {
        int idx;
        if (dense) {
            idx = target;
            if (idx < 0 || idx >= counts.length) {
                position = -1;
                return false;
            }
        } else {
            idx = java.util.Arrays.binarySearch(presentDocs, target);
            if (idx < 0) {
                position = -1;
                return false;
            }
        }
        position = idx;
        cursor = flatOffsets[idx];
        cursorEnd = flatOffsets[idx + 1];
        return true;
    }

    public int docValueCount() {
        if (position < 0) {
            throw new IllegalStateException("no current doc; call advanceExact first");
        }
        return (int) counts[position];
    }

    public long nextValue() {
        if (cursor >= cursorEnd) {
            throw new IllegalStateException("no more values for current doc");
        }
        return flatValues[cursor++];
    }
}
