package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.index.seqno.SequenceNumbers;
import com.naqqa.elasticsearch.index.translog.VersionType;

public record DeleteOperation(String id, long version, VersionType versionType, long ifSeqNo, long ifPrimaryTerm) {

    public static DeleteOperation of(String id) {
        return new DeleteOperation(id, Versions.MATCH_ANY, VersionType.INTERNAL,
            SequenceNumbers.UNASSIGNED_SEQ_NO, SequenceNumbers.UNASSIGNED_PRIMARY_TERM);
    }

    public DeleteOperation withCas(long ifSeqNo, long ifPrimaryTerm) {
        return new DeleteOperation(id, version, versionType, ifSeqNo, ifPrimaryTerm);
    }
}
