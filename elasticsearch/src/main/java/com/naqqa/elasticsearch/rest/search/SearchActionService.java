package com.naqqa.elasticsearch.rest.search;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface SearchActionService {

    record MsearchItem(List<String> indices, Map<String, Object> header, Map<String, Object> body) {
    }

    CompletableFuture<Map<String, Object>> search(List<String> indices, Map<String, Object> requestBody, Map<String, String> params);

    CompletableFuture<List<Map<String, Object>>> multiSearch(List<MsearchItem> items);

    CompletableFuture<Map<String, Object>> count(List<String> indices, Map<String, Object> requestBody, Map<String, String> params);

    CompletableFuture<Map<String, Object>> explain(String index, String id, Map<String, Object> requestBody, Map<String, String> params);

    CompletableFuture<Map<String, Object>> validateQuery(List<String> indices, Map<String, Object> requestBody, Map<String, String> params);

    CompletableFuture<Map<String, Object>> fieldCaps(List<String> indices, Map<String, String> params);

    CompletableFuture<Map<String, Object>> scroll(String scrollId, String scrollTtl);

    CompletableFuture<Map<String, Object>> clearScroll(List<String> scrollIds);

    CompletableFuture<Map<String, Object>> openPointInTime(List<String> indices, Map<String, String> params);

    CompletableFuture<Map<String, Object>> closePointInTime(String pitId);

    CompletableFuture<Map<String, Object>> submitAsyncSearch(List<String> indices, Map<String, Object> requestBody, Map<String, String> params);

    CompletableFuture<Map<String, Object>> getAsyncSearch(String id, Map<String, String> params);

    CompletableFuture<Map<String, Object>> deleteAsyncSearch(String id);

    CompletableFuture<Map<String, Object>> searchTemplate(List<String> indices, Map<String, Object> requestBody, Map<String, String> params);

    CompletableFuture<Map<String, Object>> renderTemplate(Map<String, Object> requestBody);

    CompletableFuture<Map<String, Object>> rankEval(List<String> indices, Map<String, Object> requestBody);

    CompletableFuture<Map<String, Object>> termsEnum(String index, Map<String, Object> requestBody);
}
