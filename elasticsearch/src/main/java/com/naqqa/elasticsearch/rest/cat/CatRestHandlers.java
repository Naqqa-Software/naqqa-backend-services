package com.naqqa.elasticsearch.rest.cat;

import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.rest.support.RestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CatRestHandlers {

    private final CatActionService service;

    public CatRestHandlers(CatActionService service) {
        this.service = service;
    }

    private static Map<String, String> paramsOf(RestRequest request, String... extraNames) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String name : extraNames) {
            String v = request.param(name);
            if (v != null) {
                params.put(name, v);
            }
        }
        return params;
    }

    public void indices(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.indices(paramsOf(request, "index")));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void shards(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.shards(paramsOf(request, "index")));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void nodes(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.nodes(paramsOf(request)));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void health(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.health(paramsOf(request)));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void allocation(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.allocation(paramsOf(request)));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void count(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.count(paramsOf(request, "index")));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void aliases(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.aliases(paramsOf(request, "alias")));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void segments(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.segments(paramsOf(request, "index")));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void recovery(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.recovery(paramsOf(request, "index")));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void threadPool(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.threadPool(paramsOf(request)));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void master(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.master(paramsOf(request)));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void plugins(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.plugins(paramsOf(request)));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void templates(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.templates(paramsOf(request, "name")));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void fielddata(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.fielddata(paramsOf(request, "fields")));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void pendingTasks(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.pendingTasks(paramsOf(request)));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void tasks(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.tasks(paramsOf(request)));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void repositories(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.repositories(paramsOf(request)));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }

    public void snapshots(RestRequest request, RestChannel channel) {
        CatActionService.CatTable table = RestUtils.await(service.snapshots(paramsOf(request, "repository")));
        channel.sendResponse(CatTableRenderer.render(request, table));
    }
}
