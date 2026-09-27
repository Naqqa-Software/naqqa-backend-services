package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class NumericDocValuesWriter {

    public static final String CODEC_NAME = "NaqqaNumericDV";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    private NumericDocValuesWriter() {
    }

    public static void write(IndexOutput out, int maxDoc, long[] valuesByDoc, boolean[] hasValue) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        List<Integer> presentDocs = new ArrayList<>();
        List<Long> presentValues = new ArrayList<>();
        for (int d = 0; d < maxDoc; d++) {
            if (hasValue == null || hasValue[d]) {
                presentDocs.add(d);
                presentValues.add(valuesByDoc[d]);
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
        long[] values = new long[presentValues.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = presentValues.get(i);
        }
        NumericBlockCodec.writePacked(out, values);
        CodecUtil.writeFooter(out);
    }
}
