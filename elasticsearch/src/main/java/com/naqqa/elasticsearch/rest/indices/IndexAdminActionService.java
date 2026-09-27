package com.naqqa.elasticsearch.rest.indices;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface IndexAdminActionService {

    record CreateIndexRequest(String index, Map<String, Object> settings, Map<String, Object> mappings, Map<String, Object> aliases) {
    }

    record CreateIndexResult(boolean acknowledged, boolean shardsAcknowledged, String index) {
    }

    record AckResult(boolean acknowledged) {
    }

    CompletableFuture<CreateIndexResult> createIndex(CreateIndexRequest request);

    CompletableFuture<AckResult> deleteIndex(List<String> indices);

    CompletableFuture<Map<String, Object>> getIndex(List<String> indices, Map<String, String> params);

    CompletableFuture<Boolean> indexExists(List<String> indices);

    CompletableFuture<AckResult> openIndex(List<String> indices);

    CompletableFuture<AckResult> closeIndex(List<String> indices);

    CompletableFuture<AckResult> putMapping(List<String> indices, Map<String, Object> mapping);

    CompletableFuture<Map<String, Object>> getMapping(List<String> indices);

    CompletableFuture<AckResult> putSettings(List<String> indices, Map<String, Object> settings);

    CompletableFuture<Map<String, Object>> getSettings(List<String> indices, Map<String, String> params);

    CompletableFuture<AckResult> putAlias(List<String> indices, String alias, Map<String, Object> aliasBody);

    CompletableFuture<AckResult> deleteAlias(List<String> indices, List<String> aliases);

    CompletableFuture<AckResult> updateAliases(Map<String, Object> requestBody);

    CompletableFuture<Map<String, Object>> getAlias(List<String> indices, List<String> aliasNames);

    CompletableFuture<Boolean> aliasExists(List<String> indices, List<String> aliasNames);

    CompletableFuture<Map<String, Object>> refresh(List<String> indices);

    CompletableFuture<Map<String, Object>> flush(List<String> indices, Map<String, String> params);

    CompletableFuture<Map<String, Object>> forceMerge(List<String> indices, Map<String, String> params);

    CompletableFuture<AckResult> clearCache(List<String> indices, Map<String, String> params);

    CompletableFuture<Map<String, Object>> stats(List<String> indices, Map<String, String> params);

    CompletableFuture<Map<String, Object>> segments(List<String> indices);

    CompletableFuture<Map<String, Object>> recovery(List<String> indices, Map<String, String> params);

    CompletableFuture<Map<String, Object>> shardStores(List<String> indices, Map<String, String> params);

    CompletableFuture<Map<String, Object>> diskUsage(List<String> indices, Map<String, String> params);

    CompletableFuture<Map<String, Object>> resolveIndex(List<String> names);

    CompletableFuture<Map<String, Object>> rollover(String alias, String newIndex, Map<String, Object> requestBody, boolean dryRun);

    CompletableFuture<Map<String, Object>> shrink(String sourceIndex, String targetIndex, Map<String, Object> requestBody);

    CompletableFuture<Map<String, Object>> split(String sourceIndex, String targetIndex, Map<String, Object> requestBody);

    CompletableFuture<Map<String, Object>> clone(String sourceIndex, String targetIndex, Map<String, Object> requestBody);

    CompletableFuture<AckResult> freeze(String index);

    CompletableFuture<AckResult> unfreeze(String index);

    CompletableFuture<AckResult> addBlock(List<String> indices, String block);

    CompletableFuture<AckResult> putIndexTemplate(String name, Map<String, Object> template);

    CompletableFuture<Map<String, Object>> getIndexTemplate(List<String> names);

    CompletableFuture<AckResult> deleteIndexTemplate(String name);

    CompletableFuture<AckResult> putComponentTemplate(String name, Map<String, Object> template);

    CompletableFuture<Map<String, Object>> getComponentTemplate(List<String> names);

    CompletableFuture<AckResult> deleteComponentTemplate(String name);

    CompletableFuture<AckResult> putLegacyTemplate(String name, Map<String, Object> template);

    CompletableFuture<Map<String, Object>> getLegacyTemplate(List<String> names);

    CompletableFuture<AckResult> deleteLegacyTemplate(String name);

    CompletableFuture<Map<String, Object>> simulateIndex(String index, Map<String, Object> requestBody);

    CompletableFuture<AckResult> createDataStream(String name);

    CompletableFuture<AckResult> deleteDataStream(String name);

    CompletableFuture<Map<String, Object>> getDataStreams(List<String> names);
}
