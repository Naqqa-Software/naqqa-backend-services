package com.naqqa.elasticsearch.index.engine;

public final class DeleteResult {

    private final long seqNo;
    private final long primaryTerm;
    private final long version;
    private final boolean found;
    private final boolean success;
    private final Exception failure;

    private DeleteResult(long seqNo, long primaryTerm, long version, boolean found, boolean success, Exception failure) {
        this.seqNo = seqNo;
        this.primaryTerm = primaryTerm;
        this.version = version;
        this.found = found;
        this.success = success;
        this.failure = failure;
    }

    public static DeleteResult success(long seqNo, long primaryTerm, long version, boolean found) {
        return new DeleteResult(seqNo, primaryTerm, version, found, true, null);
    }

    public static DeleteResult failure(Exception failure) {
        return new DeleteResult(-1L, -1L, -1L, false, false, failure);
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

    public boolean found() {
        return found;
    }

    public boolean success() {
        return success;
    }

    public Exception failure() {
        return failure;
    }
}
