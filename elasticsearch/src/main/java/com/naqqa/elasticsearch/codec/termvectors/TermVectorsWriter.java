package com.naqqa.elasticsearch.codec.termvectors;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.Arrays;

public final class TermVectorsWriter {

    public static final String CODEC_NAME = "NaqqaTermVectors";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    public static final int HAS_POSITIONS = 1;
    public static final int HAS_OFFSETS = 2;

    private TermVectorsWriter() {
    }

    public static void write(IndexOutput out, int flags, TermVectorTerm[][] docs) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        out.writeVInt(flags);
        out.writeVInt(docs.length);
        long[] docFilePointers = new long[docs.length];
        for (int d = 0; d < docs.length; d++) {
            docFilePointers[d] = out.getFilePointer();
            TermVectorTerm[] terms = docs[d] == null ? new TermVectorTerm[0] : docs[d];
            TermVectorTerm[] sorted = terms.clone();
            Arrays.sort(sorted, (a, b) -> compare(a.term(), b.term()));
            out.writeVInt(sorted.length);
            byte[] prev = new byte[0];
            for (TermVectorTerm t : sorted) {
                int prefixLen = commonPrefixLength(prev, t.term());
                int suffixLen = t.term().length - prefixLen;
                out.writeVInt(prefixLen);
                out.writeVInt(suffixLen);
                out.writeBytes(t.term(), prefixLen, suffixLen);
                out.writeVInt(t.freq());
                if ((flags & HAS_POSITIONS) != 0) {
                    int lastPos = 0;
                    for (int i = 0; i < t.freq(); i++) {
                        out.writeVInt(t.positions()[i] - lastPos);
                        lastPos = t.positions()[i];
                    }
                }
                if ((flags & HAS_OFFSETS) != 0) {
                    int lastEnd = 0;
                    for (int i = 0; i < t.freq(); i++) {
                        out.writeVInt(t.startOffsets()[i] - lastEnd);
                        out.writeVInt(t.endOffsets()[i] - t.startOffsets()[i]);
                        lastEnd = t.endOffsets()[i];
                    }
                }
                prev = t.term();
            }
        }
        for (long fp : docFilePointers) {
            out.writeLong(fp);
        }
        out.writeInt(docs.length);
        CodecUtil.writeFooter(out);
    }

    private static int commonPrefixLength(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i] == b[i]) {
            i++;
        }
        return i;
    }

    private static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int ai = a[i] & 0xFF;
            int bi = b[i] & 0xFF;
            if (ai != bi) {
                return ai - bi;
            }
        }
        return a.length - b.length;
    }
}
