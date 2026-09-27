package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.exception.VersionConflictEngineException;
import com.naqqa.elasticsearch.index.seqno.SequenceNumbers;

public final class VersionConflictChecker {

    public static final long MATCH_ANY = -3L;
    public static final long NOT_FOUND = -1L;

    private VersionConflictChecker() {
    }

    public static void checkSeqNoConstraints(String index, String id, long currentSeqNo, long currentPrimaryTerm,
                                              long ifSeqNo, long ifPrimaryTerm) {
        if (ifSeqNo == SequenceNumbers.UNASSIGNED_SEQ_NO) {
            return;
        }
        if (currentSeqNo == SequenceNumbers.UNASSIGNED_SEQ_NO) {
            throw new VersionConflictEngineException(index, id,
                "document does not exist (expected seq_no [{}], primary_term [{}])", ifSeqNo, ifPrimaryTerm);
        }
        if (ifSeqNo != currentSeqNo || ifPrimaryTerm != currentPrimaryTerm) {
            throw new VersionConflictEngineException(index, id,
                "seq no mismatch (got [{}][{}], expected [{}][{}])", currentSeqNo, currentPrimaryTerm, ifSeqNo, ifPrimaryTerm);
        }
    }

    public static long checkVersionAndComputeNew(String index, String id, boolean exists, long currentVersion,
                                                   long expectedVersion, VersionType versionType, boolean isCreate) {
        if (isCreate && exists) {
            throw new VersionConflictEngineException(index, id,
                "[{}]: version conflict, document already exists (current version [{}])", id, currentVersion);
        }
        long baseline = exists ? currentVersion : NOT_FOUND;
        switch (versionType) {
            case INTERNAL: {
                if (expectedVersion != MATCH_ANY && baseline != expectedVersion) {
                    throw new VersionConflictEngineException(index, id, baseline, expectedVersion);
                }
                return (exists ? currentVersion : 0L) + 1;
            }
            case EXTERNAL: {
                if (expectedVersion <= baseline) {
                    throw new VersionConflictEngineException(index, id,
                        "[{}]: version conflict, current version [{}] is higher or equal to the one provided [{}]", id, baseline, expectedVersion);
                }
                return expectedVersion;
            }
            case EXTERNAL_GTE: {
                if (expectedVersion < baseline) {
                    throw new VersionConflictEngineException(index, id,
                        "[{}]: version conflict, current version [{}] is higher than the one provided [{}]", id, baseline, expectedVersion);
                }
                return expectedVersion;
            }
            default:
                throw new IllegalArgumentException("unknown version type " + versionType);
        }
    }
}
