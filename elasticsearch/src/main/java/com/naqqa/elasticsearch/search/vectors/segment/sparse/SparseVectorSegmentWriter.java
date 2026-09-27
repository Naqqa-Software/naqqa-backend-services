package com.naqqa.elasticsearch.search.vectors.segment.sparse;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.IOContext;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SparseVectorSegmentWriter {

    public static final String CODEC_NAME = "NaqqaSparseVectors";
    public static final int VERSION_CURRENT = 1;

    private SparseVectorSegmentWriter() {
    }

    public static void write(Directory dir, String fileName, int maxDoc, List<SparseVectorEntry> entries) throws IOException {
        List<SparseVectorEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(SparseVectorEntry::docId));
        for (SparseVectorEntry e : sorted) {
            if (e.docId() < 0 || e.docId() >= maxDoc) {
                throw new IllegalArgumentException("docId " + e.docId() + " out of range [0," + maxDoc + ")");
            }
        }
        try (IndexOutput out = dir.createOutput(fileName, IOContext.DEFAULT)) {
            CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
            out.writeVInt(maxDoc);
            out.writeVInt(sorted.size());
            for (SparseVectorEntry e : sorted) {
                out.writeVInt(e.docId());
                Map<String, Float> features = new TreeMap<>(e.features());
                out.writeVInt(features.size());
                for (Map.Entry<String, Float> f : features.entrySet()) {
                    out.writeString(f.getKey());
                    out.writeInt(Float.floatToIntBits(f.getValue()));
                }
            }
            CodecUtil.writeFooter(out);
        }
    }
}
