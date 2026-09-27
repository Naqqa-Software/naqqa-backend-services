package com.naqqa.elasticsearch.http;

import com.naqqa.elasticsearch.test.Assert;

import java.util.List;
import java.util.Map;

public final class DefaultRestErrorRendererTest {

    @com.naqqa.elasticsearch.test.Test
    public void mapsIllegalArgumentExceptionTo400() {
        DefaultRestErrorRenderer renderer = new DefaultRestErrorRenderer();
        IllegalArgumentException error = new IllegalArgumentException("bad request field");
        Assert.assertEquals(400, renderer.statusFor(error));

        Map<String, Object> body = renderer.render(error, false);
        Assert.assertEquals(400, body.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> errorObject = (Map<String, Object>) body.get("error");
        Assert.assertEquals("illegal_argument_exception", errorObject.get("type"));
        Assert.assertEquals("bad request field", errorObject.get("reason"));
        Assert.assertFalse(errorObject.containsKey("stack_trace"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rootCause = (List<Map<String, Object>>) errorObject.get("root_cause");
        Assert.assertEquals(1, rootCause.size());
        Assert.assertEquals("illegal_argument_exception", rootCause.get(0).get("type"));
    }

    @com.naqqa.elasticsearch.test.Test
    public void includesStackTraceWhenErrorTraceRequested() {
        DefaultRestErrorRenderer renderer = new DefaultRestErrorRenderer();
        RuntimeException error = new RuntimeException("boom");
        Map<String, Object> body = renderer.render(error, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> errorObject = (Map<String, Object>) body.get("error");
        Assert.assertTrue(((String) errorObject.get("stack_trace")).contains("RuntimeException"));
    }

    @com.naqqa.elasticsearch.test.Test
    public void customStatusProviderIsRespected() {
        DefaultRestErrorRenderer renderer = new DefaultRestErrorRenderer();
        RuntimeException withStatus = new RuntimeException("nope") {
        };
        Assert.assertEquals(500, renderer.statusFor(withStatus));

        RuntimeException custom = new CustomStatusException();
        Assert.assertEquals(429, renderer.statusFor(custom));
    }

    private static final class CustomStatusException extends RuntimeException implements RestStatusProvider {
        @Override
        public int restStatus() {
            return 429;
        }
    }
}
