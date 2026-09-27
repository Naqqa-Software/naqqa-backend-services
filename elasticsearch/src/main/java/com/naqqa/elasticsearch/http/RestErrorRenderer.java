package com.naqqa.elasticsearch.http;

import java.util.Map;

public interface RestErrorRenderer {

    int statusFor(Throwable error);

    Map<String, Object> render(Throwable error, boolean errorTrace);
}
