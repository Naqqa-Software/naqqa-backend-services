package com.naqqa.elasticsearch.http;

@FunctionalInterface
public interface RestHandler {
    void handleRequest(RestRequest request, RestChannel channel) throws Exception;
}
