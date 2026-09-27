package com.naqqa.elasticsearch.index.engine;

public final class NoOpResult {

    private final long seqNo;
    private final long primaryTerm;
    private final boolean success;
    private final Exception failure;

    private NoOpResult(long seqNo, long primaryTerm, boolean success, Exception failure) {
        this.seqNo = seqNo;
        this.primaryTerm = primaryTerm;
        this.success = success;
        this.failure = failure;
    }

    public static NoOpResult success(long seqNo, long primaryTerm) {
        return new NoOpResult(seqNo, primaryTerm, true, null);
    }

    public static NoOpResult failure(Exception failure) {
        return new NoOpResult(-1L, -1L, false, failure);
    }

    public long seqNo() {
        return seqNo;
    }

    public long primaryTerm() {
        return primaryTerm;
    }

    public boolean success() {
        return success;
    }

    public Exception failure() {
        return failure;
    }
}
