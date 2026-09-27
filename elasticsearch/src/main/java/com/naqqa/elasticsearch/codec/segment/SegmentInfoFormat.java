package com.naqqa.elasticsearch.codec.segment;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

public final class SegmentInfoFormat {

    public static final String CODEC_NAME = "NaqqaSegmentInfo";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    private SegmentInfoFormat() {
    }

    public static void write(IndexOutput out, SegmentInfo info) throws IOException {
        CodecUtil.writeIndexHeader(out, CODEC_NAME, VERSION_CURRENT, info.id(), "");
        out.writeString(info.name());
        out.writeVInt(info.maxDoc());
        out.writeString(info.codecName());
        out.writeSetOfStrings(info.files());
        out.writeMapOfStrings(info.diagnostics());
        out.writeMapOfStrings(info.attributes());
        out.writeString(info.indexSort() == null ? "" : info.indexSort());
        CodecUtil.writeFooter(out);
    }

    public static SegmentInfo read(IndexInput in, String expectedName, byte[] expectedId) throws IOException {
        CodecUtil.checksumEntireFile(in);
        CodecUtil.checkIndexHeader(in, CODEC_NAME, VERSION_START, VERSION_CURRENT, expectedId, "");
        String name = in.readString();
        int maxDoc = in.readVInt();
        String codecName = in.readString();
        var files = new LinkedHashSet<>(in.readSetOfStrings());
        var diagnostics = new LinkedHashMap<>(in.readMapOfStrings());
        var attributes = new LinkedHashMap<>(in.readMapOfStrings());
        String indexSort = in.readString();
        if (!name.equals(expectedName)) {
            throw new IOException("segment name mismatch: expected " + expectedName + " got " + name);
        }
        return new SegmentInfo(name, expectedId, maxDoc, codecName, files, diagnostics, attributes,
            indexSort.isEmpty() ? null : indexSort);
    }
}
