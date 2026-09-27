package com.naqqa.elasticsearch.codec.docvalues;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class SortedDocValuesWriter {

    public static final String CODEC_NAME = "NaqqaSortedDV";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    private SortedDocValuesWriter() {
    }

    public static void write(IndexOutput out, int maxDoc, byte[][] valuesByDoc) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        TreeSet<byte[]> dictSet = new TreeSet<>(SimpleTermDictionary::compare);
        for (byte[] v : valuesByDoc) {
            if (v != null) {
                dictSet.add(v);
            }
        }
        byte[][] dict = dictSet.toArray(new byte[0][]);
        SimpleTermDictionary.write(out, dict);

        List<Integer> presentDocs = new ArrayList<>();
        List<Long> ords = new ArrayList<>();
        for (int d = 0; d < maxDoc; d++) {
            if (valuesByDoc[d] != null) {
                presentDocs.add(d);
                ords.add((long) SimpleTermDictionary.lookupOrd(dict, valuesByDoc[d]));
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
        long[] ordArray = new long[ords.size()];
        for (int i = 0; i < ordArray.length; i++) {
            ordArray[i] = ords.get(i);
        }
        NumericBlockCodec.writePacked(out, ordArray);
        CodecUtil.writeFooter(out);
    }
}
