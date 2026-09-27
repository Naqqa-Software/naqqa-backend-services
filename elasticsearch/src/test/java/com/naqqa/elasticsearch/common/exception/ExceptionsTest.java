package com.naqqa.elasticsearch.common.exception;

import com.naqqa.elasticsearch.common.xcontent.ToXContent;
import com.naqqa.elasticsearch.common.xcontent.XContentGenerator;
import com.naqqa.elasticsearch.common.json.JsonGenerator;
import com.naqqa.elasticsearch.common.json.JsonParser;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.io.StringWriter;
import java.util.Map;

public class ExceptionsTest {

    @Test
    public void testExceptionNameSnakeCase() {
        Assert.assertEquals("index_not_found_exception", ElasticsearchException.getExceptionName(new IndexNotFoundException("foo")));
        Assert.assertEquals("illegal_argument_exception", ElasticsearchException.getExceptionName(new IllegalArgumentException("x")));
    }

    @Test
    public void testRestStatusMapping() {
        Assert.assertEquals(RestStatus.NOT_FOUND, new IndexNotFoundException("idx").status());
        Assert.assertEquals(RestStatus.CONFLICT, new VersionConflictEngineException("idx", "1", 2, 3).status());
        Assert.assertEquals(RestStatus.TOO_MANY_REQUESTS, new EsRejectedExecutionException("busy").status());
        Assert.assertEquals(RestStatus.BAD_REQUEST, ExceptionsHelper.status(new IllegalArgumentException("bad")));
    }

    @Test
    public void testToXContentRendering() {
        IndexNotFoundException ex = new IndexNotFoundException("my-index");
        StringWriter sw = new StringWriter();
        XContentGenerator gen = new JsonGenerator(sw, false);
        gen.writeStartObject();
        ElasticsearchException.generateFailureXContent(gen, ToXContent.EMPTY_PARAMS, ex, true);
        gen.writeEndObject();
        gen.flush();
        String json = sw.toString();

        JsonParser parser = new JsonParser(json);
        parser.nextToken();
        Map<?, ?> map = (Map<?, ?>) parser.readValue();
        Map<?, ?> error = (Map<?, ?>) map.get("error");
        Assert.assertEquals("index_not_found_exception", error.get("type"));
        Assert.assertEquals(404, map.get("status"));
    }

    @Test
    public void testCausedByChain() {
        RuntimeException cause = new RuntimeException("root cause");
        ElasticsearchException wrapper = new ElasticsearchException("wrapped failure", cause);
        Assert.assertSame(cause, wrapper.getCause());
        Throwable[] roots = wrapper.guessRootCauses();
        Assert.assertEquals(1, roots.length);
    }
}
