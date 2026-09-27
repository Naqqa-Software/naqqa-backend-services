package com.naqqa.elasticsearch.codec.vectors;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;

public final class VectorsFormat {

    public static final String CODEC_NAME = "NaqqaVectors";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    public static final byte ELEMENT_FLOAT = 0;
    public static final byte ELEMENT_BYTE = 1;

    private VectorsFormat() {
    }

    public static void writeFloatVectors(IndexOutput out, int dims, float[][] vectorsByDoc, byte[] annGraphBlob) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        out.writeByte(ELEMENT_FLOAT);
        out.writeVInt(dims);
        out.writeVInt(vectorsByDoc.length);
        for (float[] v : vectorsByDoc) {
            out.writeByte((byte) (v == null ? 0 : 1));
            if (v != null) {
                for (float f : v) {
                    out.writeInt(Float.floatToIntBits(f));
                }
            }
        }
        writeBlob(out, annGraphBlob);
        CodecUtil.writeFooter(out);
    }

    public static void writeByteVectors(IndexOutput out, int dims, byte[][] vectorsByDoc, byte[] annGraphBlob) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        out.writeByte(ELEMENT_BYTE);
        out.writeVInt(dims);
        out.writeVInt(vectorsByDoc.length);
        for (byte[] v : vectorsByDoc) {
            out.writeByte((byte) (v == null ? 0 : 1));
            if (v != null) {
                out.writeBytes(v, 0, v.length);
            }
        }
        writeBlob(out, annGraphBlob);
        CodecUtil.writeFooter(out);
    }

    private static void writeBlob(IndexOutput out, byte[] blob) throws IOException {
        if (blob == null) {
            out.writeVInt(0);
        } else {
            out.writeVInt(blob.length);
            out.writeBytes(blob, 0, blob.length);
        }
    }
}
