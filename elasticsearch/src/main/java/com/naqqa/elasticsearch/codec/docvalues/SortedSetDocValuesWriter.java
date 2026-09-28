package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.Arrays;

public final class SortedSetDocValuesWriter {

    public static final String CODEC_NAME = "NaqqaSortedSetDV";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    private SortedSetDocValuesWriter() {
    }

    public static void write(IndexOutput out, int maxDoc, byte[][][] valuesByDoc) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);

        int totalValues = 0;
        int presentCount = 0;
        for (byte[][] docValues : valuesByDoc) {
            if (docValues != null && docValues.length > 0) {
                presentCount++;
                totalValues += docValues.length;
            }
        }

        byte[][] allValues = new byte[totalValues][];
        int pos = 0;
        for (byte[][] docValues : valuesByDoc) {
            if (docValues != null) {
                for (byte[] v : docValues) {
                    allValues[pos++] = v;
                }
            }
        }

        byte[][] dict = dedupSorted(allValues);
        SimpleTermDictionary.write(out, dict);

        int[] presentDocs = new int[presentCount];
        long[] counts = new long[presentCount];
        long[] flatOrds = new long[totalValues];
        int docIdx = 0;
        int ordPos = 0;
        int[] ordBuf = new int[8];
        for (int d = 0; d < maxDoc; d++) {
            byte[][] docValues = valuesByDoc[d];
            if (docValues == null || docValues.length == 0) {
                continue;
            }
            presentDocs[docIdx] = d;
            int n = docValues.length;
            if (n == 1) {
                flatOrds[ordPos++] = SimpleTermDictionary.lookupOrd(dict, docValues[0]);
                counts[docIdx++] = 1;
                continue;
            }
            if (ordBuf.length < n) {
                ordBuf = new int[n];
            }
            for (int i = 0; i < n; i++) {
                ordBuf[i] = SimpleTermDictionary.lookupOrd(dict, docValues[i]);
            }
            Arrays.sort(ordBuf, 0, n);
            int unique = 0;
            for (int i = 0; i < n; i++) {
                if (i == 0 || ordBuf[i] != ordBuf[unique - 1]) {
                    ordBuf[unique++] = ordBuf[i];
                }
            }
            for (int i = 0; i < unique; i++) {
                flatOrds[ordPos++] = ordBuf[i];
            }
            counts[docIdx++] = unique;
        }
        out.writeVInt(maxDoc);
        out.writeVInt(presentCount);
        boolean dense = presentCount == maxDoc;
        out.writeByte((byte) (dense ? 1 : 0));
        if (!dense) {
            int prev = -1;
            for (int d : presentDocs) {
                out.writeVInt(d - prev - 1);
                prev = d;
            }
        }
        NumericBlockCodec.writePacked(out, counts);
        NumericBlockCodec.writePacked(out, ordPos == flatOrds.length ? flatOrds : Arrays.copyOf(flatOrds, ordPos));
        CodecUtil.writeFooter(out);
    }

    private static byte[][] dedupSorted(byte[][] values) {
        byte[][] sorted = values.clone();
        Arrays.sort(sorted, SimpleTermDictionary::compare);
        int unique = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (unique == 0 || SimpleTermDictionary.compare(sorted[unique - 1], sorted[i]) != 0) {
                sorted[unique++] = sorted[i];
            }
        }
        return unique == sorted.length ? sorted : Arrays.copyOf(sorted, unique);
    }
}
