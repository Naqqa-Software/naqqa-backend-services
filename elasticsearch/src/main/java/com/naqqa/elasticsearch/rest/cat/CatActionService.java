package com.naqqa.elasticsearch.rest.cat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface CatActionService {

    record CatTable(List<String> columns, List<Map<String, String>> rows) {
    }

    CompletableFuture<CatTable> indices(Map<String, String> params);

    CompletableFuture<CatTable> shards(Map<String, String> params);

    CompletableFuture<CatTable> nodes(Map<String, String> params);

    CompletableFuture<CatTable> health(Map<String, String> params);

    CompletableFuture<CatTable> allocation(Map<String, String> params);

    CompletableFuture<CatTable> count(Map<String, String> params);

    CompletableFuture<CatTable> aliases(Map<String, String> params);

    CompletableFuture<CatTable> segments(Map<String, String> params);

    CompletableFuture<CatTable> recovery(Map<String, String> params);

    CompletableFuture<CatTable> threadPool(Map<String, String> params);

    CompletableFuture<CatTable> master(Map<String, String> params);

    CompletableFuture<CatTable> plugins(Map<String, String> params);

    CompletableFuture<CatTable> templates(Map<String, String> params);

    CompletableFuture<CatTable> fielddata(Map<String, String> params);

    CompletableFuture<CatTable> pendingTasks(Map<String, String> params);

    CompletableFuture<CatTable> tasks(Map<String, String> params);

    CompletableFuture<CatTable> repositories(Map<String, String> params);

    CompletableFuture<CatTable> snapshots(Map<String, String> params);
}
