package com.naqqa.elasticsearch.http;

public interface RestChannel {

    RestRequest request();

    void sendResponse(RestResponse response);
}
