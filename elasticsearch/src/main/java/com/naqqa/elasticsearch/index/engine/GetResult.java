package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.common.bytes.BytesReference;

public final class GetResult {

    private final boolean exists;
    private final String id;
    private final long version;
    private final long seqNo;
    private final long primaryTerm;
    private final BytesReference source;

    private GetResult(boolean exists, String id, long version, long seqNo, long primaryTerm, BytesReference source) {
        this.exists = exists;
        this.id = id;
        this.version = version;
        this.seqNo = seqNo;
        this.primaryTerm = primaryTerm;
        this.source = source;
    }

    public static GetResult found(String id, long version, long seqNo, long primaryTerm, BytesReference source) {
        return new GetResult(true, id, version, seqNo, primaryTerm, source);
    }

    public static GetResult notFound(String id) {
        return new GetResult(false, id, Versions.NOT_FOUND, -1L, -1L, null);
    }

    public boolean exists() {
        return exists;
    }

    public String id() {
        return id;
    }

    public long version() {
        return version;
    }

    public long seqNo() {
        return seqNo;
    }

    public long primaryTerm() {
        return primaryTerm;
    }

    public BytesReference source() {
        return source;
    }
}
