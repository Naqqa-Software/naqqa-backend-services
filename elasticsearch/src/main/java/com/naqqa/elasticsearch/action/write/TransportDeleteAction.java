package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.index.engine.DeleteOperation;
import com.naqqa.elasticsearch.index.engine.DeleteResult;
import com.naqqa.elasticsearch.index.replication.WaitForActiveShards;
import com.naqqa.elasticsearch.index.translog.VersionType;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;

import java.io.IOException;

public final class TransportDeleteAction {

    private final RelocationAwareRouter router;

    public TransportDeleteAction(RelocationAwareRouter router) {
        this.router = router;
    }

    public DocumentActionService.DeleteResult execute(DocumentActionService.DeleteRequest request) throws IOException {
        return execute(request, WaitForActiveShards.DEFAULT);
    }

    public DocumentActionService.DeleteResult execute(DocumentActionService.DeleteRequest request,
                                                        WaitForActiveShards waitForActiveShards) throws IOException {
        RefreshPolicy policy = RefreshPolicy.parse(request.refresh());
        IndexNameResolver.Resolution resolution = router.resolveIndex(request.index());
        String index = resolution.index();
        String routing = request.routing() != null ? request.routing() : resolution.routingOverride();
        DeleteResult result = router.execute(index, request.id(), routing, group -> {
            DeleteOperation op = buildOperation(request);
            DeleteResult deleteResult = group.replicateDelete(op, waitForActiveShards);
            if (deleteResult.success()) {
                RefreshCoordinator.apply(policy, group.primary().indexShard());
            }
            return deleteResult;
        });

        if (!result.success()) {
            throw TransportIndexAction.translateFailure(request.id(), result.failure());
        }
        return new DocumentActionService.DeleteResult(index, request.id(), result.found(), result.version(),
            result.seqNo(), result.primaryTerm(), result.found() ? "deleted" : "not_found");
    }

    static DeleteOperation buildOperation(DocumentActionService.DeleteRequest request) {
        DeleteOperation op = DeleteOperation.of(request.id());
        if (request.version() != null) {
            VersionType versionType = TransportIndexAction.parseVersionType(request.versionType());
            op = new DeleteOperation(request.id(), request.version(), versionType, op.ifSeqNo(), op.ifPrimaryTerm());
        }
        if (request.ifSeqNo() != null) {
            long primaryTerm = request.ifPrimaryTerm() != null ? request.ifPrimaryTerm() : 0L;
            op = op.withCas(request.ifSeqNo(), primaryTerm);
        }
        return op;
    }
}
