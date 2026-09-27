package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SortedNumericDocValuesWriter {

    public static final String CODEC_NAME = "NaqqaSortedNumericDV";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    private SortedNumericDocValuesWriter() {
    }

    public static void write(IndexOutput out, int maxDoc, long[][] valuesByDoc) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        List<Integer> presentDocs = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        List<Long> flatValues = new ArrayList<>();
        for (int d = 0; d < maxDoc; d++) {
            long[] docValues = valuesByDoc[d];
            if (docValues != null && docValues.length > 0) {
                presentDocs.add(d);
                long[] sorted = docValues.clone();
                Arrays.sort(sorted);
                counts.add((long) sorted.length);
                for (long v : sorted) {
                    flatValues.add(v);
                }
            }
        }
        out.writeVInt(maxDoc);
        out.writeVInt(presentDocs.size());
        boolean dense = presentDocs.size() == maxDoc;
        out.writeByte((byte) (dense ? 1 : 0));
        if (!dense) {
            int prev = -1;
            for (int d : presentDocs) {
                out.writeVInt(d - prev - 1);
                prev = d;
            }
        }
        NumericBlockCodec.writePacked(out, toArray(counts));
        NumericBlockCodec.writePacked(out, toArray(flatValues));
        CodecUtil.writeFooter(out);
    }

    private static long[] toArray(List<Long> list) {
        long[] result = new long[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = list.get(i);
        }
        return result;
    }
}
