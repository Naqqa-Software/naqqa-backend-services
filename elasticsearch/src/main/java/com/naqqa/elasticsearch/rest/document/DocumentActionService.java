package com.naqqa.elasticsearch.rest.document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface DocumentActionService {

    record IndexRequest(String index, String id, Map<String, Object> source, String routing, Long version,
                         String versionType, Long ifSeqNo, Long ifPrimaryTerm, String opType, String refresh,
                         String pipeline) {
    }

    record IndexResult(String index, String id, long version, long seqNo, long primaryTerm, String result,
                        boolean created, int shardsTotal, int shardsSuccessful) {
    }

    record GetRequest(String index, String id, String routing, boolean sourceEnabled, List<String> sourceIncludes,
                       List<String> sourceExcludes, List<String> storedFields, Long version, boolean realtime,
                       boolean refresh) {
    }

    record GetResult(String index, String id, boolean found, long version, long seqNo, long primaryTerm,
                      Map<String, Object> source) {
    }

    record DeleteRequest(String index, String id, String routing, Long version, String versionType, Long ifSeqNo,
                          Long ifPrimaryTerm, String refresh) {
    }

    record DeleteResult(String index, String id, boolean found, long version, long seqNo, long primaryTerm,
                         String result) {
    }

    record UpdateRequest(String index, String id, Map<String, Object> doc, Map<String, Object> upsert,
                          boolean docAsUpsert, boolean detectNoop, int retryOnConflict, Map<String, Object> script,
                          Long ifSeqNo, Long ifPrimaryTerm, String refresh, boolean sourceEnabled) {
    }

    record UpdateResult(String index, String id, long version, long seqNo, long primaryTerm, String result,
                         boolean noop, Map<String, Object> getSource) {
    }

    record BulkItem(String action, String index, String id, Map<String, Object> source, Map<String, Object> doc,
                     Map<String, Object> upsert, boolean docAsUpsert, Long version, String versionType, Long ifSeqNo,
                     Long ifPrimaryTerm, String routing, String opType) {
    }

    record BulkItemResult(String action, String index, String id, int status, long version, long seqNo,
                           long primaryTerm, String result, boolean found, Map<String, Object> error) {
    }

    record BulkResult(List<BulkItemResult> items, long tookMillis) {
    }

    record MultiGetResult(List<GetResult> docs) {
    }

    CompletableFuture<IndexResult> index(IndexRequest request);

    CompletableFuture<GetResult> get(GetRequest request);

    CompletableFuture<DeleteResult> delete(DeleteRequest request);

    CompletableFuture<UpdateResult> update(UpdateRequest request);

    CompletableFuture<BulkResult> bulk(List<BulkItem> items, String defaultIndex, String globalRefresh);

    CompletableFuture<MultiGetResult> multiGet(List<GetRequest> requests);

    CompletableFuture<Map<String, Object>> deleteByQuery(String index, Map<String, Object> requestBody, Map<String, String> params);

    CompletableFuture<Map<String, Object>> updateByQuery(String index, Map<String, Object> requestBody, Map<String, String> params);

    CompletableFuture<Map<String, Object>> reindex(Map<String, Object> requestBody);

    CompletableFuture<Map<String, Object>> termVectors(String index, String id, Map<String, Object> requestBody);

    CompletableFuture<Map<String, Object>> multiTermVectors(Map<String, Object> requestBody);

    CompletableFuture<Map<String, Object>> rethrottle(String taskId, Double requestsPerSecond);
}
