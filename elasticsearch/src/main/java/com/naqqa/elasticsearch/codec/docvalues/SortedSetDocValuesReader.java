package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;

public final class SortedSetDocValuesReader {

    private final byte[][] dict;
    private final int maxDoc;
    private final boolean dense;
    private final int[] presentDocs;
    private final long[] counts;
    private final long[] flatOrds;
    private final int[] flatOffsets;
    private int position = -1;
    private int cursor;
    private int cursorEnd;

    public SortedSetDocValuesReader(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        CodecUtil.checkHeader(in, SortedSetDocValuesWriter.CODEC_NAME, SortedSetDocValuesWriter.VERSION_START, SortedSetDocValuesWriter.VERSION_CURRENT);
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
        this.counts = NumericBlockCodec.readPacked(in, presentCount);
        this.flatOffsets = new int[presentCount + 1];
        int total = 0;
        for (int i = 0; i < presentCount; i++) {
            flatOffsets[i] = total;
            total += (int) counts[i];
        }
        flatOffsets[presentCount] = total;
        this.flatOrds = NumericBlockCodec.readPacked(in, total);
    }

    public byte[] lookupOrd(int ord) {
        return dict[ord];
    }

    public boolean advanceExact(int target) {
        int idx;
        if (dense) {
            idx = target;
            if (idx < 0 || idx >= (presentDocs == null ? maxDoc : presentDocs.length)) {
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

    public long nextOrd() {
        if (cursor >= cursorEnd) {
            throw new IllegalStateException("no more ordinals for current doc");
        }
        return flatOrds[cursor++];
    }
}
