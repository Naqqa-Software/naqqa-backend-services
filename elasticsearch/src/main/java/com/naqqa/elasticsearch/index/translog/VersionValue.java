package com.naqqa.elasticsearch.index.translog;

public final class VersionValue {

    private final long version;
    private final long seqNo;
    private final long term;
    private final Translog.Location location;
    private final boolean delete;
    private final long timestamp;

    public VersionValue(long version, long seqNo, long term, Translog.Location location, boolean delete) {
        this.version = version;
        this.seqNo = seqNo;
        this.term = term;
        this.location = location;
        this.delete = delete;
        this.timestamp = System.currentTimeMillis();
    }

    public static VersionValue index(long version, long seqNo, long term, Translog.Location location) {
        return new VersionValue(version, seqNo, term, location, false);
    }

    public static VersionValue tombstone(long version, long seqNo, long term, Translog.Location location) {
        return new VersionValue(version, seqNo, term, location, true);
    }

    public long version() {
        return version;
    }

    public long seqNo() {
        return seqNo;
    }

    public long term() {
        return term;
    }

    public Translog.Location location() {
        return location;
    }

    public boolean isDelete() {
        return delete;
    }

    public long timestamp() {
        return timestamp;
    }
}
