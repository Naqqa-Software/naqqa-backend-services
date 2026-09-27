package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.common.UUIDs;
import com.naqqa.elasticsearch.common.exception.VersionConflictEngineException;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.replication.WaitForActiveShards;
import com.naqqa.elasticsearch.index.translog.VersionType;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.rest.support.VersionConflictException;

import java.io.IOException;

public final class TransportIndexAction {

    private final RelocationAwareRouter router;

    public TransportIndexAction(RelocationAwareRouter router) {
        this.router = router;
    }

    public DocumentActionService.IndexResult execute(DocumentActionService.IndexRequest request) throws IOException {
        return execute(request, WaitForActiveShards.DEFAULT);
    }

    public DocumentActionService.IndexResult execute(DocumentActionService.IndexRequest request,
                                                       WaitForActiveShards waitForActiveShards) throws IOException {
        String id = request.id() != null ? request.id() : UUIDs.base64TimeBasedUUID();
        IndexNameResolver.Resolution resolution = router.resolveIndex(request.index());
        String index = resolution.index();
        String routing = request.routing() != null ? request.routing() : resolution.routingOverride();
        boolean create = "create".equals(request.opType());
        RefreshPolicy policy = RefreshPolicy.parse(request.refresh());

        IndexResult result = router.execute(index, id, routing, group -> {
            if (create) {
                GetResult existing = group.primary().indexShard().get(id);
                if (existing.exists()) {
                    throw new VersionConflictException("[" + id + "]: version conflict, document already exists "
                        + "(current version [" + existing.version() + "])");
                }
            }
            IndexOperation op = buildOperation(id, routing, request);
            IndexResult indexResult = group.replicateIndex(op, waitForActiveShards);
            if (indexResult.success()) {
                RefreshCoordinator.apply(policy, group.primary().indexShard());
            }
            return indexResult;
        });

        if (!result.success()) {
            throw translateFailure(id, result.failure());
        }
        return new DocumentActionService.IndexResult(index, id, result.version(), result.seqNo(),
            result.primaryTerm(), result.created() ? "created" : "updated", result.created(), 1, 1);
    }

    static IndexOperation buildOperation(String id, String routing, DocumentActionService.IndexRequest request) {
        IndexOperation op = IndexOperation.of(id, routing, request.source() == null ? java.util.Map.of() : request.source());
        if (request.version() != null) {
            op = op.withVersion(request.version(), parseVersionType(request.versionType()));
        }
        if (request.ifSeqNo() != null) {
            long primaryTerm = request.ifPrimaryTerm() != null ? request.ifPrimaryTerm() : 0L;
            op = op.withCas(request.ifSeqNo(), primaryTerm);
        }
        return op;
    }

    static VersionType parseVersionType(String versionType) {
        if (versionType == null) {
            return VersionType.INTERNAL;
        }
        return switch (versionType) {
            case "external" -> VersionType.EXTERNAL;
            case "external_gte" -> VersionType.EXTERNAL_GTE;
            default -> VersionType.INTERNAL;
        };
    }

    static RuntimeException translateFailure(String id, Exception failure) {
        if (failure instanceof VersionConflictEngineException) {
            return new VersionConflictException(failure.getMessage());
        }
        if (failure instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(failure);
    }
}
