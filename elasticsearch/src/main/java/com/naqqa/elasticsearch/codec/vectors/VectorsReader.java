package com.naqqa.elasticsearch.codec.vectors;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;

public final class VectorsReader {

    private final byte elementType;
    private final int dims;
    private final float[][] floatVectors;
    private final byte[][] byteVectors;
    private final byte[] annGraphBlob;

    public VectorsReader(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        CodecUtil.checkHeader(in, VectorsFormat.CODEC_NAME, VectorsFormat.VERSION_START, VectorsFormat.VERSION_CURRENT);
        this.elementType = in.readByte();
        this.dims = in.readVInt();
        int docCount = in.readVInt();
        if (elementType == VectorsFormat.ELEMENT_FLOAT) {
            floatVectors = new float[docCount][];
            byteVectors = null;
            for (int d = 0; d < docCount; d++) {
                if (in.readByte() != 0) {
                    float[] v = new float[dims];
                    for (int i = 0; i < dims; i++) {
                        v[i] = Float.intBitsToFloat(in.readInt());
                    }
                    floatVectors[d] = v;
                }
            }
        } else {
            byteVectors = new byte[docCount][];
            floatVectors = null;
            for (int d = 0; d < docCount; d++) {
                if (in.readByte() != 0) {
                    byte[] v = new byte[dims];
                    in.readBytes(v, 0, dims);
                    byteVectors[d] = v;
                }
            }
        }
        int blobLen = in.readVInt();
        this.annGraphBlob = new byte[blobLen];
        in.readBytes(annGraphBlob, 0, blobLen);
    }

    public int dims() {
        return dims;
    }

    public float[] floatVector(int docId) {
        return floatVectors[docId];
    }

    public byte[] byteVector(int docId) {
        return byteVectors[docId];
    }

    public byte[] annGraphBlob() {
        return annGraphBlob;
    }
}
