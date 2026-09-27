package com.naqqa.elasticsearch.codec.postings;

public final class PostingsFlags {

    public static final int DOCS_ONLY = 0;
    public static final int FREQS = 1;
    public static final int POSITIONS = 2 | FREQS;
    public static final int OFFSETS = 4 | POSITIONS;
    public static final int PAYLOADS = 8 | POSITIONS;
    public static final int ALL = OFFSETS | PAYLOADS;

    private PostingsFlags() {
    }

    public static boolean hasFreqs(int flags) {
        return (flags & FREQS) != 0;
    }

    public static boolean hasPositions(int flags) {
        return (flags & (2)) != 0;
    }

    public static boolean hasOffsets(int flags) {
        return (flags & 4) != 0;
    }

    public static boolean hasPayloads(int flags) {
        return (flags & 8) != 0;
    }
}
