package com.naqqa.elasticsearch.codec.livedocs;

import com.naqqa.elasticsearch.store.CodecUtil;
import com.naqqa.elasticsearch.store.IndexInput;
import com.naqqa.elasticsearch.store.IndexOutput;

import java.io.IOException;

public final class LiveDocsFormat {

    public static final String CODEC_NAME = "NaqqaLiveDocs";
    public static final int VERSION_START = 1;
    public static final int VERSION_CURRENT = 1;

    private LiveDocsFormat() {
    }

    public static void write(IndexOutput out, FixedBitSet liveDocs, long generation) throws IOException {
        CodecUtil.writeHeader(out, CODEC_NAME, VERSION_CURRENT);
        out.writeVLong(generation);
        out.writeVInt(liveDocs.length());
        long[] words = liveDocs.words();
        out.writeVInt(words.length);
        for (long w : words) {
            out.writeLong(w);
        }
        CodecUtil.writeFooter(out);
    }

    public static FixedBitSet read(IndexInput in) throws IOException {
        CodecUtil.checksumEntireFile(in);
        CodecUtil.checkHeader(in, CODEC_NAME, VERSION_START, VERSION_CURRENT);
        in.readVLong();
        int numBits = in.readVInt();
        int numWords = in.readVInt();
        long[] words = new long[numWords];
        for (int i = 0; i < numWords; i++) {
            words[i] = in.readLong();
        }
        return new FixedBitSet(words, numBits);
    }

    public static long readGeneration(IndexInput in) throws IOException {
        IndexInput clone = in.clone();
        clone.seek(0);
        CodecUtil.checkHeader(clone, CODEC_NAME, VERSION_START, VERSION_CURRENT);
        return clone.readVLong();
    }
}
