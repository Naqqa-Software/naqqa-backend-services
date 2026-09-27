package com.naqqa.elasticsearch.http;

import com.naqqa.elasticsearch.test.Assert;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CorsHandlerTest {

    @com.naqqa.elasticsearch.test.Test
    public void preflightMatchesAllowedOriginAndSetsHeaders() {
        CorsConfig config = new CorsConfig.Builder()
            .enabled(true)
            .allowOrigin("http://example.com")
            .allowCredentials(true)
            .build();
        CorsHandler handler = new CorsHandler(config);

        RestRequest request = requestWithHeaders(RestMethod.OPTIONS, Map.of(
            "Origin", "http://example.com",
            "Access-Control-Request-Method", "GET"
        ));

        Optional<RestResponse> preflight = handler.handlePreflight(request);
        Assert.assertTrue(preflight.isPresent());
        RestResponse response = preflight.get();
        Assert.assertEquals(204, response.status());
        Assert.assertEquals("http://example.com", response.headers().getFirst("Access-Control-Allow-Origin"));
        Assert.assertEquals("true", response.headers().getFirst("Access-Control-Allow-Credentials"));
    }

    @com.naqqa.elasticsearch.test.Test
    public void preflightRejectsUnknownOrigin() {
        CorsConfig config = new CorsConfig.Builder().enabled(true).allowOrigin("http://good.com").build();
        CorsHandler handler = new CorsHandler(config);
        RestRequest request = requestWithHeaders(RestMethod.OPTIONS, Map.of(
            "Origin", "http://evil.com",
            "Access-Control-Request-Method", "GET"
        ));
        Assert.assertTrue(handler.handlePreflight(request).isEmpty());
    }

    @com.naqqa.elasticsearch.test.Test
    public void regexOriginMatchesSubdomains() {
        CorsConfig config = new CorsConfig.Builder().enabled(true).allowOrigin("/https:\\/\\/.*\\.example\\.com/").build();
        Assert.assertTrue(config.matchesOrigin("https://foo.example.com"));
        Assert.assertFalse(config.matchesOrigin("https://foo.example.org"));
    }

    @com.naqqa.elasticsearch.test.Test
    public void disabledCorsProducesNoPreflight() {
        CorsConfig config = CorsConfig.disabled();
        CorsHandler handler = new CorsHandler(config);
        RestRequest request = requestWithHeaders(RestMethod.OPTIONS, Map.of(
            "Origin", "http://example.com",
            "Access-Control-Request-Method", "GET"
        ));
        Assert.assertTrue(handler.handlePreflight(request).isEmpty());
    }

    private static RestRequest requestWithHeaders(RestMethod method, Map<String, String> headerMap) {
        HttpHeaders headers = new HttpHeaders();
        headerMap.forEach(headers::add);
        return new RestRequest(method, "/", "/", Map.of(), headers, new byte[0]);
    }
}
