package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.index.seqno.SequenceNumbers;
import com.naqqa.elasticsearch.index.translog.VersionType;

import java.util.Map;

public record IndexOperation(String id, String routing, Map<String, Object> source, long version,
                              VersionType versionType, long ifSeqNo, long ifPrimaryTerm) {

    public static IndexOperation of(String id, Map<String, Object> source) {
        return new IndexOperation(id, null, source, Versions.MATCH_ANY, VersionType.INTERNAL,
            SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
    }

    public static IndexOperation of(String id, String routing, Map<String, Object> source) {
        return new IndexOperation(id, routing, source, Versions.MATCH_ANY, VersionType.INTERNAL,
            SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
    }

    public IndexOperation withCas(long ifSeqNo, long ifPrimaryTerm) {
        return new IndexOperation(id, routing, source, version, versionType, ifSeqNo, ifPrimaryTerm);
    }

    public IndexOperation withVersion(long version, VersionType versionType) {
        return new IndexOperation(id, routing, source, version, versionType, ifSeqNo, ifPrimaryTerm);
    }
}
