package com.naqqa.elasticsearch.codec.fieldinfos;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FieldInfosFormat {

    public static final String CODEC_NAME = "NaqqaFieldInfos";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    private FieldInfosFormat() {
    }

    public static void write(IndexOutput out, FieldInfo[] infos) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        out.writeVInt(infos.length);
        for (FieldInfo info : infos) {
            out.writeString(info.name());
            out.writeVInt(info.number());
            byte flags = (byte) ((info.indexed() ? 1 : 0) | (info.hasNorms() ? 2 : 0)
                | (info.hasVectors() ? 4 : 0) | (info.hasPayloads() ? 8 : 0));
            out.writeByte(flags);
            out.writeVInt(info.indexOptions());
            out.writeByte((byte) info.docValuesType().ordinal());
            out.writeVInt(info.pointDimensionCount());
            out.writeVInt(info.pointNumBytes());
            out.writeMapOfStrings(info.attributes());
        }
        CodecUtil.writeFooter(out);
    }

    public static FieldInfo[] read(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        CodecUtil.checkHeader(in, CODEC_NAME, VERSION_START, VERSION_CURRENT);
        int count = in.readVInt();
        FieldInfo[] result = new FieldInfo[count];
        for (int i = 0; i < count; i++) {
            String name = in.readString();
            int number = in.readVInt();
            byte flags = in.readByte();
            int indexOptions = in.readVInt();
            DocValuesType dvType = DocValuesType.values()[in.readByte() & 0xFF];
            int pointDims = in.readVInt();
            int pointBytes = in.readVInt();
            Map<String, String> attrs = new LinkedHashMap<>(in.readMapOfStrings());
            result[i] = new FieldInfo(name, number, (flags & 1) != 0, indexOptions, (flags & 2) != 0,
                (flags & 4) != 0, (flags & 8) != 0, dvType, pointDims, pointBytes, attrs);
        }
        return result;
    }
}
