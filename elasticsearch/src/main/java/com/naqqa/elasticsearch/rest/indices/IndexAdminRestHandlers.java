package com.naqqa.elasticsearch.rest.indices;

import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.rest.support.RestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IndexAdminRestHandlers {

    private final IndexAdminActionService service;

    public IndexAdminRestHandlers(IndexAdminActionService service) {
        this.service = service;
    }

    @SuppressWarnings("unchecked")
    public void createIndex(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> settings = body.get("settings") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        Map<String, Object> mappings = body.get("mappings") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        Map<String, Object> aliases = body.get("aliases") instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        IndexAdminActionService.CreateIndexResult result = RestUtils.await(
            service.createIndex(new IndexAdminActionService.CreateIndexRequest(index, settings, mappings, aliases)));
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("acknowledged", result.acknowledged());
        responseBody.put("shards_acknowledged", result.shardsAcknowledged());
        responseBody.put("index", result.index());
        RestUtils.sendJson(channel, request, 200, responseBody);
    }

    public void deleteIndex(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        IndexAdminActionService.AckResult result = RestUtils.await(service.deleteIndex(indices));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void getIndex(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.getIndex(indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void existsIndex(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        boolean exists = RestUtils.await(service.indexExists(indices));
        channel.sendResponse(RestResponse.empty(exists ? 200 : 404));
    }

    public void openIndex(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        IndexAdminActionService.AckResult result = RestUtils.await(service.openIndex(indices));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged(), "shards_acknowledged", true));
    }

    public void closeIndex(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        IndexAdminActionService.AckResult result = RestUtils.await(service.closeIndex(indices));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("acknowledged", result.acknowledged());
        body.put("shards_acknowledged", true);
        body.put("indices", Map.of());
        RestUtils.sendJson(channel, request, 200, body);
    }

    public void putMapping(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        RestUtils.requireContent(request);
        Map<String, Object> mapping = RestUtils.parseBody(request);
        IndexAdminActionService.AckResult result = RestUtils.await(service.putMapping(indices, mapping));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void getMapping(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.getMapping(indices));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void putSettings(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        RestUtils.requireContent(request);
        Map<String, Object> settings = RestUtils.parseBody(request);
        IndexAdminActionService.AckResult result = RestUtils.await(service.putSettings(indices, settings));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void getSettings(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.getSettings(indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void putAlias(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        String alias = request.param("alias");
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        IndexAdminActionService.AckResult result = RestUtils.await(service.putAlias(indices, alias, body));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void deleteAlias(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        List<String> aliases = request.paramAsList("alias");
        IndexAdminActionService.AckResult result = RestUtils.await(service.deleteAlias(indices, aliases));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void getAlias(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        List<String> aliases = request.paramAsList("alias");
        Map<String, Object> result = RestUtils.await(service.getAlias(indices, aliases));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void existsAlias(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        List<String> aliases = request.paramAsList("alias");
        boolean exists = RestUtils.await(service.aliasExists(indices, aliases));
        channel.sendResponse(RestResponse.empty(exists ? 200 : 404));
    }

    public void updateAliases(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        IndexAdminActionService.AckResult result = RestUtils.await(service.updateAliases(body));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void refresh(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.refresh(indices));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void flush(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.flush(indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void forceMerge(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.forceMerge(indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void clearCache(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        IndexAdminActionService.AckResult result = RestUtils.await(service.clearCache(indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void stats(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.stats(indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void segments(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.segments(indices));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void recovery(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.recovery(indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void shardStores(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.shardStores(indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void diskUsage(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        Map<String, Object> result = RestUtils.await(service.diskUsage(indices, Map.of()));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void resolveIndex(RestRequest request, RestChannel channel) {
        List<String> names = request.paramAsList("name");
        Map<String, Object> result = RestUtils.await(service.resolveIndex(names));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void rollover(RestRequest request, RestChannel channel) {
        String alias = request.param("alias");
        String newIndex = request.hasParam("target") ? request.param("target") : null;
        boolean dryRun = request.paramAsBoolean("dry_run", false);
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.rollover(alias, newIndex, body, dryRun));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void shrink(RestRequest request, RestChannel channel) {
        String source = request.param("index");
        String target = request.param("target");
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.shrink(source, target, body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void split(RestRequest request, RestChannel channel) {
        String source = request.param("index");
        String target = request.param("target");
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.split(source, target, body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void clone(RestRequest request, RestChannel channel) {
        String source = request.param("index");
        String target = request.param("target");
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.clone(source, target, body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void freeze(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        IndexAdminActionService.AckResult result = RestUtils.await(service.freeze(index));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged(), "shards_acknowledged", true));
    }

    public void unfreeze(RestRequest request, RestChannel channel) {
        String index = request.param("index");
        IndexAdminActionService.AckResult result = RestUtils.await(service.unfreeze(index));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged(), "shards_acknowledged", true));
    }

    public void addBlock(RestRequest request, RestChannel channel) {
        List<String> indices = request.paramAsList("index");
        String block = request.param("block");
        IndexAdminActionService.AckResult result = RestUtils.await(service.addBlock(indices, block));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged(), "shards_acknowledged", true, "indices", List.of()));
    }

    public void putIndexTemplate(RestRequest request, RestChannel channel) {
        String name = request.param("name");
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        IndexAdminActionService.AckResult result = RestUtils.await(service.putIndexTemplate(name, body));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void getIndexTemplate(RestRequest request, RestChannel channel) {
        List<String> names = request.paramAsList("name");
        Map<String, Object> result = RestUtils.await(service.getIndexTemplate(names));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void deleteIndexTemplate(RestRequest request, RestChannel channel) {
        String name = request.param("name");
        IndexAdminActionService.AckResult result = RestUtils.await(service.deleteIndexTemplate(name));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void putComponentTemplate(RestRequest request, RestChannel channel) {
        String name = request.param("name");
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        IndexAdminActionService.AckResult result = RestUtils.await(service.putComponentTemplate(name, body));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void getComponentTemplate(RestRequest request, RestChannel channel) {
        List<String> names = request.paramAsList("name");
        Map<String, Object> result = RestUtils.await(service.getComponentTemplate(names));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void deleteComponentTemplate(RestRequest request, RestChannel channel) {
        String name = request.param("name");
        IndexAdminActionService.AckResult result = RestUtils.await(service.deleteComponentTemplate(name));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void putLegacyTemplate(RestRequest request, RestChannel channel) {
        String name = request.param("name");
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        IndexAdminActionService.AckResult result = RestUtils.await(service.putLegacyTemplate(name, body));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void getLegacyTemplate(RestRequest request, RestChannel channel) {
        List<String> names = request.paramAsList("name");
        Map<String, Object> result = RestUtils.await(service.getLegacyTemplate(names));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void deleteLegacyTemplate(RestRequest request, RestChannel channel) {
        String name = request.param("name");
        IndexAdminActionService.AckResult result = RestUtils.await(service.deleteLegacyTemplate(name));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void simulateIndex(RestRequest request, RestChannel channel) {
        String name = request.param("name");
        Map<String, Object> body = request.hasContent() ? RestUtils.parseBody(request) : new LinkedHashMap<>();
        Map<String, Object> result = RestUtils.await(service.simulateIndex(name, body));
        RestUtils.sendJson(channel, request, 200, result);
    }

    public void createDataStream(RestRequest request, RestChannel channel) {
        String name = request.param("name");
        IndexAdminActionService.AckResult result = RestUtils.await(service.createDataStream(name));
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", result.acknowledged()));
    }

    public void deleteDataStream(RestRequest request, RestChannel channel) {
        List<String> names = request.paramAsList("name");
        for (String name : names) {
            RestUtils.await(service.deleteDataStream(name));
        }
        RestUtils.sendJson(channel, request, 200, Map.of("acknowledged", true));
    }

    public void getDataStreams(RestRequest request, RestChannel channel) {
        List<String> names = request.paramAsList("name");
        Map<String, Object> result = RestUtils.await(service.getDataStreams(names));
        RestUtils.sendJson(channel, request, 200, result);
    }
}
