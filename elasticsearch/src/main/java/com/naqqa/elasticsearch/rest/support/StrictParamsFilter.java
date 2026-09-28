package com.naqqa.elasticsearch.rest.support;

import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestHandler;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StrictParamsFilter {

    private StrictParamsFilter() {
    }

    public static RestHandler wrap(RestHandler delegate, Set<String> declaredParams) {
        return (request, channel) -> {
            AtomicBoolean checked = new AtomicBoolean(false);
            RestChannel checkingChannel = new RestChannel() {
                @Override
                public RestRequest request() {
                    return channel.request();
                }

                @Override
                public void sendResponse(RestResponse response) {
                    if (checked.compareAndSet(false, true)) {
                        StrictParams.check(request, declaredParams);
                    }
                    channel.sendResponse(response);
                }
            };
            try {
                delegate.handleRequest(request, checkingChannel);
            } catch (Exception e) {
                if (checked.compareAndSet(false, true)) {
                    StrictParams.check(request, declaredParams);
                }
                throw e;
            }
        };
    }
}
