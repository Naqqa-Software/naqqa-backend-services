package com.naqqa.elasticsearch.rest.document;

import com.naqqa.elasticsearch.common.UUIDs;
import com.naqqa.elasticsearch.common.regex.Regex;
import com.naqqa.elasticsearch.rest.support.DocumentMissingException;
import com.naqqa.elasticsearch.rest.support.VersionConflictException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryDocumentActionService implements DocumentActionService {

    public static final class StoredDoc {
        public Map<String, Object> source;
        public long version;
        public long seqNo;
        public long primaryTerm = 1;
        public boolean deleted;
    }

    private final Map<String, Map<String, StoredDoc>> indices = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> seqNoCounters = new ConcurrentHashMap<>();

    public Map<String, Map<String, StoredDoc>> rawIndices() {
        return indices;
    }

    private Map<String, StoredDoc> indexStore(String index) {
        return indices.computeIfAbsent(index, k -> new ConcurrentHashMap<>());
    }

    private long nextSeqNo(String index) {
        return seqNoCounters.computeIfAbsent(index, k -> new AtomicLong(-1)).incrementAndGet();
    }

    @Override
    public CompletableFuture<IndexResult> index(IndexRequest request) {
        Map<String, StoredDoc> store = indexStore(request.index());
        String id = request.id() != null ? request.id() : UUIDs.base64TimeBasedUUID();
        StoredDoc existing = store.get(id);
        boolean existedLive = existing != null && !existing.deleted;

        if ("create".equals(request.opType()) && existedLive) {
            return CompletableFuture.failedFuture(new VersionConflictException(
                "[" + id + "]: version conflict, document already exists (current version [" + existing.version + "])"));
        }
        if (request.ifSeqNo() != null) {
            long currentSeq = existing != null ? existing.seqNo : -1;
            long currentTerm = existing != null ? existing.primaryTerm : 0;
            if (currentSeq != request.ifSeqNo() || (request.ifPrimaryTerm() != null && currentTerm != request.ifPrimaryTerm())) {
                return CompletableFuture.failedFuture(new VersionConflictException(
                    "[" + id + "]: version conflict, required seqNo [" + request.ifSeqNo() + "], primary term ["
                        + request.ifPrimaryTerm() + "], current seqNo [" + currentSeq + "], current primary term [" + currentTerm + "]"));
            }
        }
        long currentVersion = existing != null ? existing.version : 0;
        long newVersion;
        if ("external".equals(request.versionType()) && request.version() != null) {
            if (existing != null && request.version() <= currentVersion) {
                return CompletableFuture.failedFuture(new VersionConflictException(
                    "[" + id + "]: version conflict, current version [" + currentVersion + "] is higher or equal to the one provided [" + request.version() + "]"));
            }
            newVersion = request.version();
        } else {
            newVersion = currentVersion + 1;
        }

        StoredDoc doc = existing != null ? existing : new StoredDoc();
        boolean created = existing == null || existing.deleted;
        doc.source = new LinkedHashMap<>(request.source() == null ? Map.of() : request.source());
        doc.version = newVersion;
        doc.seqNo = nextSeqNo(request.index());
        doc.deleted = false;
        store.put(id, doc);

        return CompletableFuture.completedFuture(new IndexResult(
            request.index(), id, doc.version, doc.seqNo, doc.primaryTerm, created ? "created" : "updated", created, 1, 1));
    }

    @Override
    public CompletableFuture<GetResult> get(GetRequest request) {
        Map<String, StoredDoc> store = indices.get(request.index());
        StoredDoc doc = store == null ? null : store.get(request.id());
        if (doc == null || doc.deleted) {
            return CompletableFuture.completedFuture(new GetResult(request.index(), request.id(), false, -1, -1, 0, null));
        }
        Map<String, Object> source = request.sourceEnabled() ? filterSource(doc.source, request.sourceIncludes(), request.sourceExcludes()) : null;
        return CompletableFuture.completedFuture(new GetResult(request.index(), request.id(), true, doc.version, doc.seqNo, doc.primaryTerm, source));
    }

    static Map<String, Object> filterSource(Map<String, Object> source, List<String> includes, List<String> excludes) {
        if (source == null) {
            return null;
        }
        if ((includes == null || includes.isEmpty()) && (excludes == null || excludes.isEmpty())) {
            return new LinkedHashMap<>(source);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            boolean included = includes == null || includes.isEmpty() || matchesAny(includes, key);
            boolean excluded = excludes != null && matchesAny(excludes, key);
            if (included && !excluded) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static boolean matchesAny(List<String> patterns, String field) {
        for (String pattern : patterns) {
            if (Regex.isSimpleMatchPattern(pattern) ? Regex.simpleMatch(pattern, field) : pattern.equals(field)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompletableFuture<DeleteResult> delete(DeleteRequest request) {
        Map<String, StoredDoc> store = indexStore(request.index());
        StoredDoc doc = store.get(request.id());
        if (doc == null || doc.deleted) {
            return CompletableFuture.completedFuture(new DeleteResult(request.index(), request.id(), false,
                doc == null ? 1 : doc.version + 1, nextSeqNo(request.index()), 1, "not_found"));
        }
        if (request.ifSeqNo() != null && doc.seqNo != request.ifSeqNo()) {
            return CompletableFuture.failedFuture(new VersionConflictException(
                "[" + request.id() + "]: version conflict, required seqNo [" + request.ifSeqNo() + "], current seqNo [" + doc.seqNo + "]"));
        }
        doc.deleted = true;
        doc.version = doc.version + 1;
        doc.seqNo = nextSeqNo(request.index());
        return CompletableFuture.completedFuture(new DeleteResult(request.index(), request.id(), true, doc.version, doc.seqNo, doc.primaryTerm, "deleted"));
    }

    @Override
    public CompletableFuture<UpdateResult> update(UpdateRequest request) {
        Map<String, StoredDoc> store = indexStore(request.index());
        StoredDoc doc = store.get(request.id());
        boolean missing = doc == null || doc.deleted;
        if (missing) {
            Map<String, Object> upsertSource = request.docAsUpsert() ? request.doc() : request.upsert();
            if (upsertSource == null) {
                return CompletableFuture.failedFuture(new DocumentMissingException(request.index(), request.id()));
            }
            StoredDoc created = doc != null ? doc : new StoredDoc();
            created.source = new LinkedHashMap<>(upsertSource);
            created.version = created.version + 1;
            created.seqNo = nextSeqNo(request.index());
            created.deleted = false;
            store.put(request.id(), created);
            Map<String, Object> resultSource = request.sourceEnabled() ? new LinkedHashMap<>(created.source) : null;
            return CompletableFuture.completedFuture(new UpdateResult(request.index(), request.id(), created.version, created.seqNo,
                created.primaryTerm, "created", false, resultSource));
        }
        if (request.ifSeqNo() != null && doc.seqNo != request.ifSeqNo()) {
            return CompletableFuture.failedFuture(new VersionConflictException(
                "[" + request.id() + "]: version conflict, required seqNo [" + request.ifSeqNo() + "], current seqNo [" + doc.seqNo + "]"));
        }
        Map<String, Object> merged = new LinkedHashMap<>(doc.source);
        boolean changed = false;
        if (request.doc() != null) {
            for (Map.Entry<String, Object> entry : request.doc().entrySet()) {
                Object previous = merged.put(entry.getKey(), entry.getValue());
                if (!java.util.Objects.equals(previous, entry.getValue())) {
                    changed = true;
                }
            }
        }
        if (request.detectNoop() && !changed) {
            Map<String, Object> resultSource = request.sourceEnabled() ? new LinkedHashMap<>(doc.source) : null;
            return CompletableFuture.completedFuture(new UpdateResult(request.index(), request.id(), doc.version, doc.seqNo,
                doc.primaryTerm, "noop", true, resultSource));
        }
        doc.source = merged;
        doc.version = doc.version + 1;
        doc.seqNo = nextSeqNo(request.index());
        Map<String, Object> resultSource = request.sourceEnabled() ? new LinkedHashMap<>(doc.source) : null;
        return CompletableFuture.completedFuture(new UpdateResult(request.index(), request.id(), doc.version, doc.seqNo,
            doc.primaryTerm, "updated", false, resultSource));
    }

    @Override
    public CompletableFuture<BulkResult> bulk(List<BulkItem> items, String defaultIndex, String globalRefresh) {
        long start = System.nanoTime();
        List<BulkItemResult> results = new ArrayList<>(items.size());
        for (BulkItem item : items) {
            String index = item.index() != null ? item.index() : defaultIndex;
            try {
                switch (item.action()) {
                    case "index", "create" -> {
                        IndexResult r = await(index(new IndexRequest(index, item.id(), item.source(), item.routing(),
                            item.version(), item.versionType(), item.ifSeqNo(), item.ifPrimaryTerm(), item.action().equals("create") ? "create" : item.opType(),
                            "false", null)));
                        results.add(new BulkItemResult(item.action(), index, r.id(), r.created() ? 201 : 200, r.version(), r.seqNo(), r.primaryTerm(), r.result(), true, null));
                    }
                    case "delete" -> {
                        DeleteResult r = await(delete(new DeleteRequest(index, item.id(), item.routing(), item.version(),
                            item.versionType(), item.ifSeqNo(), item.ifPrimaryTerm(), "false")));
                        results.add(new BulkItemResult("delete", index, item.id(), r.found() ? 200 : 404, r.version(), r.seqNo(), r.primaryTerm(), r.result(), r.found(), null));
                    }
                    case "update" -> {
                        UpdateRequest ur = new UpdateRequest(index, item.id(), item.doc(), item.upsert(), item.docAsUpsert(),
                            false, 0, null, item.ifSeqNo(), item.ifPrimaryTerm(), "false", false);
                        UpdateResult r = await(update(ur));
                        results.add(new BulkItemResult("update", index, item.id(), r.result().equals("created") ? 201 : 200, r.version(), r.seqNo(), r.primaryTerm(), r.result(), true, null));
                    }
                    default -> throw new IllegalArgumentException("unsupported bulk action [" + item.action() + "]");
                }
            } catch (RuntimeException e) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("type", typeNameOf(e));
                error.put("reason", e.getMessage());
                int status = e instanceof com.naqqa.elasticsearch.http.RestStatusProvider p ? p.restStatus() : 400;
                results.add(new BulkItemResult(item.action(), index, item.id(), status, -1, -1, 0, null, false, error));
            }
        }
        long tookMillis = (System.nanoTime() - start) / 1_000_000;
        return CompletableFuture.completedFuture(new BulkResult(results, tookMillis));
    }

    private static String typeNameOf(Throwable t) {
        String simple = t.getClass().getSimpleName();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        String snake = sb.toString();
        return snake.endsWith("_exception") ? snake : snake + "_exception";
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    @Override
    public CompletableFuture<MultiGetResult> multiGet(List<GetRequest> requests) {
        List<GetResult> results = new ArrayList<>(requests.size());
        for (GetRequest request : requests) {
            results.add(await(get(request)));
        }
        return CompletableFuture.completedFuture(new MultiGetResult(results));
    }

    @Override
    public CompletableFuture<Map<String, Object>> deleteByQuery(String index, Map<String, Object> requestBody, Map<String, String> params) {
        Map<String, StoredDoc> store = indices.get(index);
        int deleted = 0;
        boolean matchAll = isMatchAll(requestBody);
        if (store != null && matchAll) {
            for (StoredDoc doc : store.values()) {
                if (!doc.deleted) {
                    doc.deleted = true;
                    doc.version++;
                    doc.seqNo = nextSeqNo(index);
                    deleted++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("took", 0);
        result.put("timed_out", false);
        result.put("total", deleted);
        result.put("deleted", deleted);
        result.put("batches", 1);
        result.put("version_conflicts", 0);
        result.put("noops", 0);
        result.put("retries", Map.of("bulk", 0, "search", 0));
        result.put("throttled_millis", 0);
        result.put("requests_per_second", -1.0);
        result.put("throttled_until_millis", 0);
        result.put("failures", List.of());
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> updateByQuery(String index, Map<String, Object> requestBody, Map<String, String> params) {
        Map<String, StoredDoc> store = indices.get(index);
        int updated = 0;
        boolean matchAll = isMatchAll(requestBody);
        if (store != null && matchAll) {
            for (StoredDoc doc : store.values()) {
                if (!doc.deleted) {
                    doc.version++;
                    doc.seqNo = nextSeqNo(index);
                    updated++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("took", 0);
        result.put("timed_out", false);
        result.put("total", updated);
        result.put("updated", updated);
        result.put("deleted", 0);
        result.put("batches", 1);
        result.put("version_conflicts", 0);
        result.put("noops", 0);
        result.put("retries", Map.of("bulk", 0, "search", 0));
        result.put("throttled_millis", 0);
        result.put("requests_per_second", -1.0);
        result.put("throttled_until_millis", 0);
        result.put("failures", List.of());
        return CompletableFuture.completedFuture(result);
    }

    @SuppressWarnings("unchecked")
    private static boolean isMatchAll(Map<String, Object> requestBody) {
        if (requestBody == null) {
            return true;
        }
        Object query = requestBody.get("query");
        if (query == null) {
            return true;
        }
        if (query instanceof Map<?, ?> m) {
            return m.containsKey("match_all") || m.isEmpty();
        }
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> reindex(Map<String, Object> requestBody) {
        Map<String, Object> sourceSpec = (Map<String, Object>) requestBody.get("source");
        Map<String, Object> destSpec = (Map<String, Object>) requestBody.get("dest");
        if (sourceSpec == null || destSpec == null) {
            throw new IllegalArgumentException("reindex requires [source] and [dest]");
        }
        String sourceIndex = String.valueOf(sourceSpec.get("index"));
        String destIndex = String.valueOf(destSpec.get("index"));
        Map<String, StoredDoc> sourceStore = indices.get(sourceIndex);
        Map<String, StoredDoc> destStore = indexStore(destIndex);
        int created = 0;
        int updated = 0;
        if (sourceStore != null) {
            for (Map.Entry<String, StoredDoc> entry : sourceStore.entrySet()) {
                if (entry.getValue().deleted) {
                    continue;
                }
                StoredDoc existing = destStore.get(entry.getKey());
                boolean isCreate = existing == null;
                StoredDoc target = existing != null ? existing : new StoredDoc();
                target.source = new LinkedHashMap<>(entry.getValue().source);
                target.version = target.version + 1;
                target.seqNo = nextSeqNo(destIndex);
                target.deleted = false;
                destStore.put(entry.getKey(), target);
                if (isCreate) {
                    created++;
                } else {
                    updated++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("took", 0);
        result.put("timed_out", false);
        result.put("total", created + updated);
        result.put("created", created);
        result.put("updated", updated);
        result.put("deleted", 0);
        result.put("batches", 1);
        result.put("version_conflicts", 0);
        result.put("noops", 0);
        result.put("retries", Map.of("bulk", 0, "search", 0));
        result.put("throttled_millis", 0);
        result.put("requests_per_second", -1.0);
        result.put("throttled_until_millis", 0);
        result.put("failures", List.of());
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Map<String, Object>> termVectors(String index, String id, Map<String, Object> requestBody) {
        Map<String, StoredDoc> store = indices.get(index);
        StoredDoc doc = store == null ? null : store.get(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_index", index);
        result.put("_id", id);
        boolean found = doc != null && !doc.deleted;
        result.put("found", found);
        if (found) {
            result.put("term_vectors", buildTermVectors(doc.source));
        }
        return CompletableFuture.completedFuture(result);
    }

    private static Map<String, Object> buildTermVectors(Map<String, Object> source) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!(entry.getValue() instanceof String text)) {
                continue;
            }
            Map<String, Integer> freq = new LinkedHashMap<>();
            for (String token : text.toLowerCase(java.util.Locale.ROOT).split("\\W+")) {
                if (!token.isEmpty()) {
                    freq.merge(token, 1, Integer::sum);
                }
            }
            Map<String, Object> terms = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> f : freq.entrySet()) {
                terms.put(f.getKey(), Map.of("term_freq", f.getValue()));
            }
            Map<String, Object> fieldStats = new LinkedHashMap<>();
            fieldStats.put("field_statistics", Map.of("sum_doc_freq", freq.size(), "doc_count", 1, "sum_ttf", freq.values().stream().mapToInt(Integer::intValue).sum()));
            fieldStats.put("terms", terms);
            fields.put(entry.getKey(), fieldStats);
        }
        return fields;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Map<String, Object>> multiTermVectors(Map<String, Object> requestBody) {
        List<Map<String, Object>> docs = (List<Map<String, Object>>) requestBody.getOrDefault("docs", List.of());
        List<Map<String, Object>> responses = new ArrayList<>();
        for (Map<String, Object> spec : docs) {
            String index = String.valueOf(spec.get("_index"));
            String id = String.valueOf(spec.get("_id"));
            responses.add(await(termVectors(index, id, spec)));
        }
        return CompletableFuture.completedFuture(Map.of("docs", responses));
    }

    @Override
    public CompletableFuture<Map<String, Object>> rethrottle(String taskId, Double requestsPerSecond) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("acknowledged", true);
        result.put("task", taskId);
        result.put("requests_per_second", requestsPerSecond);
        return CompletableFuture.completedFuture(result);
    }
}
