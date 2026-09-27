package com.naqqa.elasticsearch.rest.document;

import com.naqqa.elasticsearch.common.json.NdJson;
import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.rest.support.RestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DocumentRestHandlers {

    private final DocumentActionService service;

    public DocumentRestHandlers(DocumentActionService service) {
        this.service = service;
    }

    public void index(RestRequest request, RestChannel channel) {
        indexInternal(request, channel, request.param("op_type", "index"));
    }

    public void create(RestRequest request, RestChannel channel) {
        indexInternal(request, channel, "create");
    }

    private void indexInternal(RestRequest request, RestChannel channel, String opType) {
        String index = request.param("index");
        if (!RestUtils.requireIndex(index)) {
            throw new IllegalArgumentException("index is required");
        }
        String id = request.hasParam("id") ? request.param("id") : null;
        RestUtils.requireContent(request);
        Map<String, Object> source = RestUtils.parseBody(request);
        Long version = RestUtils.paramAsLong(request, "version");
        Long ifSeqNo = RestUtils.paramAsLong(request, "if_seq_no");
        Long ifPrimaryTerm = RestUtils.paramAsLong(request, "if_primary_term");
        DocumentActionService.IndexRequest req = new DocumentActionService.IndexRequest(index, id, source,
            request.param("routing"), version, request.param("version_type"), ifSeqNo, ifPrimaryTerm, opType,
            request.param("refresh", "false"), request.param("pipeline"));
        DocumentActionService.IndexResult result = RestUtils.await(service.index(req));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_index", result.index());
        body.put("_id", result.id());
        body.put("_version", result.version());
        body.put("_seq_no", result.seqNo());
        body.put("_primary_term", result.primaryTerm());
        body.put("result", result.result());
        body.put("_shards", Map.of("total", result.shardsTotal(), "successful", result.shardsSuccessful(), "failed", 0));
        RestUtils.sendJson(channel, request, result.created() ? 201 : 200, body);
    }

    public void get(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        String id = request.param("id");
        boolean sourceEnabled = true;
        List<String> includes = new ArrayList<>();
        List<String> excludes = request.paramAsList("_source_excludes");
        if (excludes.isEmpty()) {
            excludes = request.paramAsList("_source_exclude");
        }
        String sourceParam = request.param("_source");
        if (sourceParam != null) {
            if (sourceParam.equalsIgnoreCase("false")) {
                sourceEnabled = false;
            } else if (!sourceParam.equalsIgnoreCase("true")) {
                includes.addAll(request.paramAsList("_source"));
            }
        }
        List<String> explicitIncludes = request.paramAsList("_source_includes");
        if (explicitIncludes.isEmpty()) {
            explicitIncludes = request.paramAsList("_source_include");
        }
        includes.addAll(explicitIncludes);
        Long version = RestUtils.paramAsLong(request, "version");
        boolean realtime = request.paramAsBoolean("realtime", true);
        boolean refresh = request.paramAsBoolean("refresh", false);
        DocumentActionService.GetRequest req = new DocumentActionService.GetRequest(index, id, request.param("routing"),
            sourceEnabled, includes, excludes, request.paramAsList("stored_fields"), version, realtime, refresh);
        DocumentActionService.GetResult result = RestUtils.await(service.get(req));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_index", result.index());
        body.put("_id", result.id());
        if (!result.found()) {
            body.put("found", false);
            RestUtils.sendJson(channel, request, 404, body);
            return;
        }
        body.put("_version", result.version());
        body.put("_seq_no", result.seqNo());
        body.put("_primary_term", result.primaryTerm());
        body.put("found", true);
        if (sourceEnabled) {
            body.put("_source", result.source());
        }
        RestUtils.sendJson(channel, request, 200, body);
    }

    public void getSource(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        String id = request.param("id");
        DocumentActionService.GetRequest req = new DocumentActionService.GetRequest(index, id, request.param("routing"), true,
            request.paramAsList("_source_includes"), request.paramAsList("_source_excludes"), List.of(), null, true, false);
        DocumentActionService.GetResult result = RestUtils.await(service.get(req));
        if (!result.found()) {
            throw new RestApiException(404, "Document not found for [" + index + "]/[" + id + "]");
        }
        Map<String, Object> source = result.source() == null ? Map.of() : result.source();
        RestUtils.sendJson(channel, request, 200, source);
    }

    public void delete(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        String id = request.param("id");
        Long version = RestUtils.paramAsLong(request, "version");
        Long ifSeqNo = RestUtils.paramAsLong(request, "if_seq_no");
        Long ifPrimaryTerm = RestUtils.paramAsLong(request, "if_primary_term");
        DocumentActionService.DeleteRequest req = new DocumentActionService.DeleteRequest(index, id, request.param("routing"),
            version, request.param("version_type"), ifSeqNo, ifPrimaryTerm, request.param("refresh", "false"));
        DocumentActionService.DeleteResult result = RestUtils.await(service.delete(req));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_index", result.index());
        body.put("_id", result.id());
        body.put("_version", result.version());
        body.put("_seq_no", result.seqNo());
        body.put("_primary_term", result.primaryTerm());
        body.put("result", result.result());
        body.put("_shards", Map.of("total", 1, "successful", 1, "failed", 0));
        RestUtils.sendJson(channel, request, result.found() ? 200 : 404, body);
    }

    @SuppressWarnings("unchecked")
    public void update(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        String id = request.param("id");
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        Map<String, Object> doc = (Map<String, Object>) body.get("doc");
        Map<String, Object> upsert = (Map<String, Object>) body.get("upsert");
        boolean docAsUpsert = Boolean.TRUE.equals(body.get("doc_as_upsert"));
        boolean detectNoop = !Boolean.FALSE.equals(body.get("detect_noop"));
        Map<String, Object> script = (Map<String, Object>) body.get("script");
        Long ifSeqNo = RestUtils.paramAsLong(request, "if_seq_no");
        Long ifPrimaryTerm = RestUtils.paramAsLong(request, "if_primary_term");
        boolean sourceEnabled = !"false".equalsIgnoreCase(request.param("_source", "true"));
        DocumentActionService.UpdateRequest req = new DocumentActionService.UpdateRequest(index, id, doc, upsert, docAsUpsert,
            detectNoop, request.paramAsInt("retry_on_conflict", 0), script, ifSeqNo, ifPrimaryTerm,
            request.param("refresh", "false"), sourceEnabled);
        DocumentActionService.UpdateResult result = RestUtils.await(service.update(req));
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("_index", result.index());
        responseBody.put("_id", result.id());
        responseBody.put("_version", result.version());
        responseBody.put("_seq_no", result.seqNo());
        responseBody.put("_primary_term", result.primaryTerm());
        responseBody.put("result", result.result());
        responseBody.put("_shards", Map.of("total", 1, "successful", 1, "failed", 0));
        if (sourceEnabled && result.getSource() != null) {
            Map<String, Object> getObj = new LinkedHashMap<>();
            getObj.put("found", true);
            getObj.put("_source", result.getSource());
            responseBody.put("get", getObj);
        }
        RestUtils.sendJson(channel, request, "created".equals(result.result()) ? 201 : 200, responseBody);
    }

    @SuppressWarnings("unchecked")
    public void bulk(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        String defaultIndex = request.param("index");
        List<Object> lines = NdJson.readAll(request.content());
        List<DocumentActionService.BulkItem> items = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            if (!(lines.get(i) instanceof Map<?, ?> rawActionLine)) {
                throw new IllegalArgumentException("malformed bulk action line at position " + i);
            }
            Map<String, Object> actionLine = (Map<String, Object>) rawActionLine;
            if (actionLine.size() != 1) {
                throw new IllegalArgumentException("malformed action/metadata line, expected a single action");
            }
            Map.Entry<String, Object> entry = actionLine.entrySet().iterator().next();
            String action = entry.getKey();
            if (!action.equals("index") && !action.equals("create") && !action.equals("update") && !action.equals("delete")) {
                throw new IllegalArgumentException("bulk action [" + action + "] not supported");
            }
            Map<String, Object> meta = entry.getValue() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String indexOverride = meta.get("_index") != null ? String.valueOf(meta.get("_index")) : null;
            String id = meta.get("_id") != null ? String.valueOf(meta.get("_id")) : null;
            String routing = meta.get("_routing") != null ? String.valueOf(meta.get("_routing")) : null;
            Long version = meta.get("version") instanceof Number n ? n.longValue() : null;
            String versionType = meta.get("version_type") != null ? String.valueOf(meta.get("version_type")) : null;
            i++;
            Map<String, Object> source = null;
            Map<String, Object> doc = null;
            Map<String, Object> upsert = null;
            boolean docAsUpsert = false;
            if (!action.equals("delete")) {
                if (i >= lines.size() || !(lines.get(i) instanceof Map<?, ?> rawBody)) {
                    throw new IllegalArgumentException("expected a source/document line after action line for [" + action + "]");
                }
                Map<String, Object> body = (Map<String, Object>) rawBody;
                i++;
                if (action.equals("update")) {
                    doc = (Map<String, Object>) body.get("doc");
                    upsert = (Map<String, Object>) body.get("upsert");
                    docAsUpsert = Boolean.TRUE.equals(body.get("doc_as_upsert"));
                } else {
                    source = body;
                }
            }
            items.add(new DocumentActionService.BulkItem(action, indexOverride, id, source, doc, upsert, docAsUpsert,
                version, versionType, null, null, routing, action.equals("create") ? "create" : null));
        }
        DocumentActionService.BulkResult result = RestUtils.await(service.bulk(items, defaultIndex, request.param("refresh", "false")));
        List<Object> responseItems = new ArrayList<>();
        boolean anyError = false;
        for (DocumentActionService.BulkItemResult item : result.items()) {
            Map<String, Object> itemBody = new LinkedHashMap<>();
            itemBody.put("_index", item.index());
            itemBody.put("_id", item.id());
            itemBody.put("status", item.status());
            if (item.error() != null) {
                itemBody.put("error", item.error());
                anyError = true;
            } else {
                itemBody.put("_version", item.version());
                itemBody.put("_seq_no", item.seqNo());
                itemBody.put("_primary_term", item.primaryTerm());
                itemBody.put("result", item.result());
                itemBody.put("_shards", Map.of("total", 1, "successful", 1, "failed", 0));
                if (item.action().equals("delete")) {
                    itemBody.put("found", item.found());
                }
            }
            responseItems.add(Map.of(item.action(), itemBody));
        }
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("took", result.tookMillis());
        responseBody.put("errors", anyError);
        responseBody.put("items", responseItems);
        RestUtils.sendJson(channel, request, 200, responseBody);
    }

    @SuppressWarnings("unchecked")
    public void mget(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        String defaultIndex = request.param("index");
        Map<String, Object> body = RestUtils.parseBody(request);
        List<Map<String, Object>> docs = new ArrayList<>();
        if (body.get("docs") instanceof List<?> rawDocs) {
            for (Object o : rawDocs) {
                docs.add((Map<String, Object>) o);
            }
        } else if (body.get("ids") instanceof List<?> rawIds) {
            for (Object id : rawIds) {
                docs.add(Map.of("_id", String.valueOf(id)));
            }
        }
        List<DocumentActionService.GetRequest> requests = new ArrayList<>();
        for (Map<String, Object> docSpec : docs) {
            String index = docSpec.get("_index") != null ? String.valueOf(docSpec.get("_index")) : defaultIndex;
            String id = String.valueOf(docSpec.get("_id"));
            String routing = docSpec.get("routing") != null ? String.valueOf(docSpec.get("routing")) : null;
            requests.add(new DocumentActionService.GetRequest(index, id, routing, true, List.of(), List.of(), List.of(), null, true, false));
        }
        DocumentActionService.MultiGetResult result = RestUtils.await(service.multiGet(requests));
        List<Object> responseDocs = new ArrayList<>();
        for (DocumentActionService.GetResult r : result.docs()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("_index", r.index());
            doc.put("_id", r.id());
            doc.put("found", r.found());
            if (r.found()) {
                doc.put("_version", r.version());
                doc.put("_seq_no", r.seqNo());
                doc.put("_primary_term", r.primaryTerm());
                doc.put("_source", r.source());
            }
            responseDocs.add(doc);
        }
        RestUtils.sendJson(channel, request, 200, Map.of("docs", responseDocs));
    }

    private static final List<String> BY_QUERY_PARAMS = List.of("refresh", "conflicts", "max_docs", "scroll_size",
        "requests_per_second", "wait_for_completion", "timeout", "routing", "slices", "preference", "scroll",
        "wait_for_active_shards", "pipeline", "q", "df", "default_operator");

    private static Map<String, String> byQueryParams(RestRequest request) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        for (String name : BY_QUERY_PARAMS) {
            String value = request.param(name);
            if (value != null) {
                params.put(name, value);
            }
        }
        return params;
    }

    public void deleteByQuery(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        if (!RestUtils.requireIndex(index)) {
            throw new IllegalArgumentException("index is required");
        }
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        Map<String, Object> result = RestUtils.await(service.deleteByQuery(index, body, byQueryParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void updateByQuery(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        if (!RestUtils.requireIndex(index)) {
            throw new IllegalArgumentException("index is required");
        }
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : Map.of();
        Map<String, Object> result = RestUtils.await(service.updateByQuery(index, body, byQueryParams(request)));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void reindex(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        Map<String, Object> result = RestUtils.await(service.reindex(body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void termVectors(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        String id = request.hasParam("id") ? request.param("id") : null;
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : Map.of();
        if (id == null && body.get("id") != null) {
            id = String.valueOf(body.get("id"));
        }
        if (id == null) {
            throw new IllegalArgumentException("id is required for termvectors, either as a path parameter or in the request body");
        }
        Map<String, Object> result = RestUtils.await(service.termVectors(index, id, body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    @SuppressWarnings("unchecked")
    public void multiTermVectors(RestRequest request, RestChannel channel) {
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>(Map.of("docs", List.of()));
        String defaultIndex = request.param("index");
        if (defaultIndex != null && body.get("docs") instanceof List<?> docs) {
            for (Object o : docs) {
                if (o instanceof Map<?, ?> m && m.get("_index") == null) {
                    ((Map<String, Object>) m).put("_index", defaultIndex);
                }
            }
        }
        Map<String, Object> result = RestUtils.await(service.multiTermVectors(body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void rethrottle(RestRequest request, RestChannel channel) {
        String taskId = request.param("taskId");
        String rps = request.param("requests_per_second");
        if (rps == null) {
            throw new IllegalArgumentException("requests_per_second is required");
        }
        Double requestsPerSecond = Double.parseDouble(rps);
        Map<String, Object> result = RestUtils.await(service.rethrottle(taskId, requestsPerSecond));
        RestUtils.sendJson(channel, request, 200, result);
    }
}
