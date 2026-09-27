package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;

public final class SortedDocValuesReader {

    private final byte[][] dict;
    private final int maxDoc;
    private final boolean dense;
    private final int[] presentDocs;
    private final long[] ords;
    private int position = -1;

    public SortedDocValuesReader(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        CodecUtil.checkHeader(in, SortedDocValuesWriter.CODEC_NAME, SortedDocValuesWriter.VERSION_START, SortedDocValuesWriter.VERSION_CURRENT);
        this.dict = SimpleTermDictionary.read(in);
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
        this.ords = NumericBlockCodec.readPacked(in, presentCount);
    }

    public int maxDoc() {
        return maxDoc;
    }

    public int valueCount() {
        return dict.length;
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

    public int ordValue() {
        if (position < 0) {
            throw new IllegalStateException("no current value; call advanceExact first");
        }
        return (int) ords[position];
    }

    public byte[] lookupOrd(int ord) {
        return dict[ord];
    }
}
