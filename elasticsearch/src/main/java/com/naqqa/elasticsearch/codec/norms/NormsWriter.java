package com.naqqa.elasticsearch.codec.norms;

import com.naqqa.elasticsearch.codec.SmallFloat;
import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;

public final class NormsWriter {

    public static final String CODEC_NAME = "NaqqaNorms";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    private NormsWriter() {
    }

    public static void write(IndexOutput out, int maxDoc, int[] fieldLengthByDoc) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        out.writeVInt(maxDoc);
        for (int d = 0; d < maxDoc; d++) {
            out.writeByte(SmallFloat.intToByte4(fieldLengthByDoc[d]));
        }
        CodecUtil.writeFooter(out);
    }
}
