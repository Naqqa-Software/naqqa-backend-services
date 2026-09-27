package com.naqqa.elasticsearch.index.engine;

public final class IndexResult {

    private final long seqNo;
    private final long primaryTerm;
    private final long version;
    private final boolean created;
    private final boolean success;
    private final Exception failure;

    private IndexResult(long seqNo, long primaryTerm, long version, boolean created, boolean success, Exception failure) {
        this.seqNo = seqNo;
        this.primaryTerm = primaryTerm;
        this.version = version;
        this.created = created;
        this.success = success;
        this.failure = failure;
    }

    public static IndexResult success(long seqNo, long primaryTerm, long version, boolean created) {
        return new IndexResult(seqNo, primaryTerm, version, created, true, null);
    }

    public static IndexResult failure(Exception failure) {
        return new IndexResult(-1L, -1L, -1L, false, false, failure);
    }

    public long seqNo() {
        return seqNo;
    }

    public long primaryTerm() {
        return primaryTerm;
    }

    public long version() {
        return version;
    }

    public boolean created() {
        return created;
    }

    public boolean success() {
        return success;
    }

    public Exception failure() {
        return failure;
    }
}
