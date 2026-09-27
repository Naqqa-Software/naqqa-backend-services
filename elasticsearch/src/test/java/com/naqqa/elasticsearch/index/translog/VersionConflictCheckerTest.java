package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.exception.VersionConflictEngineException;
import com.naqqa.elasticsearch.index.seqno.SequenceNumbers;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class VersionConflictCheckerTest {

    @Test
    public void internalVersionMustMatchThenIncrements() {
        long newVersion = VersionConflictChecker.checkVersionAndComputeNew(
            "idx", "1", true, 5L, 5L, VersionType.INTERNAL, false);
        Assert.assertEquals(6L, newVersion);
    }

    @Test
    public void internalVersionMismatchThrowsConflict() {
        Assert.assertThrows(VersionConflictEngineException.class, () ->
            VersionConflictChecker.checkVersionAndComputeNew("idx", "1", true, 5L, 4L, VersionType.INTERNAL, false));
    }

    @Test
    public void createFailsWhenDocumentAlreadyExists() {
        Assert.assertThrows(VersionConflictEngineException.class, () ->
            VersionConflictChecker.checkVersionAndComputeNew(
                "idx", "1", true, 3L, VersionConflictChecker.MATCH_ANY, VersionType.INTERNAL, true));
    }

    @Test
    public void createSucceedsWhenDocumentAbsent() {
        long newVersion = VersionConflictChecker.checkVersionAndComputeNew(
            "idx", "1", false, VersionConflictChecker.NOT_FOUND, VersionConflictChecker.MATCH_ANY, VersionType.INTERNAL, true);
        Assert.assertEquals(1L, newVersion);
    }

    @Test
    public void externalVersionMustBeStrictlyGreater() {
        long newVersion = VersionConflictChecker.checkVersionAndComputeNew(
            "idx", "1", true, 5L, 6L, VersionType.EXTERNAL, false);
        Assert.assertEquals(6L, newVersion);
        Assert.assertThrows(VersionConflictEngineException.class, () ->
            VersionConflictChecker.checkVersionAndComputeNew("idx", "1", true, 5L, 5L, VersionType.EXTERNAL, false));
        Assert.assertThrows(VersionConflictEngineException.class, () ->
            VersionConflictChecker.checkVersionAndComputeNew("idx", "1", true, 5L, 4L, VersionType.EXTERNAL, false));
    }

    @Test
    public void externalGteAllowsEqual() {
        long newVersion = VersionConflictChecker.checkVersionAndComputeNew(
            "idx", "1", true, 5L, 5L, VersionType.EXTERNAL_GTE, false);
        Assert.assertEquals(5L, newVersion);
        Assert.assertThrows(VersionConflictEngineException.class, () ->
            VersionConflictChecker.checkVersionAndComputeNew("idx", "1", true, 5L, 4L, VersionType.EXTERNAL_GTE, false));
    }

    @Test
    public void seqNoConstraintsPassWhenUnspecified() {
        VersionConflictChecker.checkSeqNoConstraints("idx", "1", 10L, 1L, SequenceNumbers.UNASSIGNED_SEQ_NO, 0L);
    }

    @Test
    public void seqNoConstraintsMismatchThrows() {
        Assert.assertThrows(VersionConflictEngineException.class, () ->
            VersionConflictChecker.checkSeqNoConstraints("idx", "1", 10L, 1L, 9L, 1L));
        Assert.assertThrows(VersionConflictEngineException.class, () ->
            VersionConflictChecker.checkSeqNoConstraints("idx", "1", 10L, 1L, 10L, 2L));
    }

    @Test
    public void seqNoConstraintsMatchSucceeds() {
        VersionConflictChecker.checkSeqNoConstraints("idx", "1", 10L, 1L, 10L, 1L);
    }

    @Test
    public void deleteOnMissingDocWithExplicitVersionConflicts() {
        Assert.assertThrows(VersionConflictEngineException.class, () ->
            VersionConflictChecker.checkVersionAndComputeNew(
                "idx", "1", false, VersionConflictChecker.NOT_FOUND, 5L, VersionType.INTERNAL, false));
    }
}
