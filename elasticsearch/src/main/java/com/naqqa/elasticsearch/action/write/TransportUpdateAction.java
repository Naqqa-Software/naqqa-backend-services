package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.engine.IndexOperation;
import com.naqqa.elasticsearch.index.engine.IndexResult;
import com.naqqa.elasticsearch.index.replication.WaitForActiveShards;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.rest.support.DocumentMissingException;
import com.naqqa.elasticsearch.rest.support.VersionConflictException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class TransportUpdateAction {

    private final RelocationAwareRouter router;
    private final UpdateScriptExecutor scriptExecutor;

    public TransportUpdateAction(RelocationAwareRouter router) {
        this(router, null);
    }

    public TransportUpdateAction(RelocationAwareRouter router, UpdateScriptExecutor scriptExecutor) {
        this.router = router;
        this.scriptExecutor = scriptExecutor;
    }

    public DocumentActionService.UpdateResult execute(DocumentActionService.UpdateRequest request) throws IOException {
        RefreshPolicy policy = RefreshPolicy.parse(request.refresh());
        int retriesLeft = Math.max(0, request.retryOnConflict());
        while (true) {
            try {
                return attempt(request, policy);
            } catch (VersionConflictException conflict) {
                if (retriesLeft <= 0) {
                    throw conflict;
                }
                retriesLeft--;
            }
        }
    }

    private DocumentActionService.UpdateResult attempt(DocumentActionService.UpdateRequest request, RefreshPolicy policy)
        throws IOException {
        IndexNameResolver.Resolution resolution = router.resolveIndex(request.index());
        String index = resolution.index();
        return router.execute(index, request.id(), resolution.routingOverride(), group -> {
            IndexShard shard = group.primary().indexShard();
            GetResult current = shard.get(request.id());

            if (!current.exists()) {
                Map<String, Object> upsertSource = request.docAsUpsert() ? request.doc() : request.upsert();
                if (upsertSource == null) {
                    throw new DocumentMissingException(index, request.id());
                }
                Map<String, Object> createSource = new LinkedHashMap<>(upsertSource);
                IndexOperation op = IndexOperation.of(request.id(), createSource);
                IndexResult indexResult = group.replicateIndex(op, WaitForActiveShards.DEFAULT);
                if (!indexResult.success()) {
                    throw TransportIndexAction.translateFailure(request.id(), indexResult.failure());
                }
                RefreshCoordinator.apply(policy, shard);
                return new DocumentActionService.UpdateResult(index, request.id(), indexResult.version(),
                    indexResult.seqNo(), indexResult.primaryTerm(), "created", false,
                    request.sourceEnabled() ? createSource : null);
            }

            if (request.ifSeqNo() != null) {
                boolean seqMismatch = current.seqNo() != request.ifSeqNo();
                boolean termMismatch = request.ifPrimaryTerm() != null && current.primaryTerm() != request.ifPrimaryTerm();
                if (seqMismatch || termMismatch) {
                    throw new VersionConflictException("[" + request.id() + "]: version conflict, required seqNo ["
                        + request.ifSeqNo() + "], current seqNo [" + current.seqNo() + "]");
                }
            }

            Map<String, Object> currentSource = SourceUtils.decode(current.source());
            Map<String, Object> merged = applyUpdate(request, currentSource);
            boolean noop = request.detectNoop() && Objects.equals(currentSource, merged);
            if (noop) {
                return new DocumentActionService.UpdateResult(index, request.id(), current.version(),
                    current.seqNo(), current.primaryTerm(), "noop", true,
                    request.sourceEnabled() ? currentSource : null);
            }

            IndexOperation op = IndexOperation.of(request.id(), merged).withCas(current.seqNo(), current.primaryTerm());
            IndexResult indexResult = group.replicateIndex(op, WaitForActiveShards.DEFAULT);
            if (!indexResult.success()) {
                throw TransportIndexAction.translateFailure(request.id(), indexResult.failure());
            }
            RefreshCoordinator.apply(policy, shard);
            return new DocumentActionService.UpdateResult(index, request.id(), indexResult.version(),
                indexResult.seqNo(), indexResult.primaryTerm(), "updated", false,
                request.sourceEnabled() ? merged : null);
        });
    }

    private Map<String, Object> applyUpdate(DocumentActionService.UpdateRequest request, Map<String, Object> currentSource) {
        Map<String, Object> result = new LinkedHashMap<>(currentSource == null ? Map.of() : currentSource);
        if (request.script() != null) {
            if (scriptExecutor == null) {
                throw new IllegalArgumentException("update script provided for [" + request.id()
                    + "] but no script executor is configured for this node");
            }
            Map<String, Object> scripted = scriptExecutor.execute(request.script(), result);
            return scripted != null ? scripted : result;
        }
        if (request.doc() != null) {
            result.putAll(request.doc());
        }
        return result;
    }
}
