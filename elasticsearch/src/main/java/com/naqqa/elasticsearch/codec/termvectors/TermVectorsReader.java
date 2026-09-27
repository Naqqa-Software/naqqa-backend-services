package com.naqqa.elasticsearch.codec.termvectors;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;

public final class TermVectorsReader {

    private final IndexInput in;
    private final int flags;
    private final long[] docFilePointers;

    public TermVectorsReader(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        this.in = in;
        CodecUtil.checkHeader(in, TermVectorsWriter.CODEC_NAME, TermVectorsWriter.VERSION_START, TermVectorsWriter.VERSION_CURRENT);
        this.flags = in.readVInt();
        int declaredDocCount = in.readVInt();
        long trailerCountPos = in.length() - CodecUtil.footerLength() - 4;
        in.seek(trailerCountPos);
        int docCount = in.readInt();
        long indexStart = trailerCountPos - 8L * docCount;
        in.seek(indexStart);
        this.docFilePointers = new long[docCount];
        for (int i = 0; i < docCount; i++) {
            docFilePointers[i] = in.readLong();
        }
        if (docCount != declaredDocCount) {
            throw new IOException("term vectors doc count mismatch");
        }
    }

    public int docCount() {
        return docFilePointers.length;
    }

    public TermVectorTerm[] get(int docId) throws IOException {
        IndexInput cursor = in.clone();
        cursor.seek(docFilePointers[docId]);
        int count = cursor.readVInt();
        TermVectorTerm[] result = new TermVectorTerm[count];
        byte[] prev = new byte[0];
        for (int i = 0; i < count; i++) {
            int prefixLen = cursor.readVInt();
            int suffixLen = cursor.readVInt();
            byte[] term = new byte[prefixLen + suffixLen];
            System.arraycopy(prev, 0, term, 0, prefixLen);
            cursor.readBytes(term, prefixLen, suffixLen);
            int freq = cursor.readVInt();
            int[] positions = null;
            int[] startOffsets = null;
            int[] endOffsets = null;
            if ((flags & TermVectorsWriter.HAS_POSITIONS) != 0) {
                positions = new int[freq];
                int lastPos = 0;
                for (int p = 0; p < freq; p++) {
                    lastPos += cursor.readVInt();
                    positions[p] = lastPos;
                }
            }
            if ((flags & TermVectorsWriter.HAS_OFFSETS) != 0) {
                startOffsets = new int[freq];
                endOffsets = new int[freq];
                int lastEnd = 0;
                for (int p = 0; p < freq; p++) {
                    int start = lastEnd + cursor.readVInt();
                    int end = start + cursor.readVInt();
                    startOffsets[p] = start;
                    endOffsets[p] = end;
                    lastEnd = end;
                }
            }
            result[i] = new TermVectorTerm(term, freq, positions, startOffsets, endOffsets);
            prev = term;
        }
        return result;
    }
}
