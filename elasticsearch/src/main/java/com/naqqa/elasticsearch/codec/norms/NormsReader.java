package com.naqqa.elasticsearch.codec.norms;

import com.naqqa.elasticsearch.codec.SmallFloat;
import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;

import java.io.IOException;

public final class NormsReader {

    private final byte[] norms;

    public NormsReader(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        CodecUtil.checkHeader(in, NormsWriter.CODEC_NAME, NormsWriter.VERSION_START, NormsWriter.VERSION_CURRENT);
        int maxDoc = in.readVInt();
        norms = new byte[maxDoc];
        in.readBytes(norms, 0, maxDoc);
    }

    public int maxDoc() {
        return norms.length;
    }

    public byte normByte(int docId) {
        return norms[docId];
    }

    public long fieldLength(int docId) {
        return SmallFloat.byte4ToInt(norms[docId]);
    }
}
