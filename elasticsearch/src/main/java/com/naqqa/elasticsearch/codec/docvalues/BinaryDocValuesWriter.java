package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class BinaryDocValuesWriter {

    public static final String CODEC_NAME = "NaqqaBinaryDV";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    private BinaryDocValuesWriter() {
    }

    public static void write(IndexOutput out, int maxDoc, byte[][] valuesByDoc) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        List<Integer> presentDocs = new ArrayList<>();
        List<byte[]> presentValues = new ArrayList<>();
        for (int d = 0; d < maxDoc; d++) {
            if (valuesByDoc[d] != null) {
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
        long[] lengths = new long[presentValues.size()];
        for (int i = 0; i < lengths.length; i++) {
            lengths[i] = presentValues.get(i).length;
        }
        NumericBlockCodec.writePacked(out, lengths);
        for (byte[] v : presentValues) {
            out.writeBytes(v, 0, v.length);
        }
        CodecUtil.writeFooter(out);
    }
}
