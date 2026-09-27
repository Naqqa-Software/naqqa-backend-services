package com.naqqa.elasticsearch.rest.cat;

import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.rest.RestTestSupport;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class CatApiTest {

    @Test
    public void testCatIndicesTextFormat() {
        RestTestSupport.Env env = RestTestSupport.fresh();
        RestTestSupport.dispatch(env.router(), RestTestSupport.request(RestMethod.PUT, "/products", Map.of(), "{}"));

        RestResponse response = RestTestSupport.dispatch(env.router(),
            RestTestSupport.request(RestMethod.GET, "/_cat/indices", Map.of("v", List.of("true")), null));
        Assert.assertEquals(200, response.status());
        String text = new String(response.content(), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("health"));
        Assert.assertTrue(text.contains("products"));
    }

    @Test
    public void testCatIndicesJsonFormat() {
        RestTestSupport.Env env = RestTestSupport.fresh();
        RestTestSupport.dispatch(env.router(), RestTestSupport.request(RestMethod.PUT, "/products", Map.of(), "{}"));

        RestResponse response = RestTestSupport.dispatch(env.router(),
            RestTestSupport.request(RestMethod.GET, "/_cat/indices", Map.of("format", List.of("json")), null));
        Assert.assertEquals(200, response.status());
        String json = new String(response.content(), StandardCharsets.UTF_8);
        try (com.naqqa.elasticsearch.common.json.JsonParser parser = new com.naqqa.elasticsearch.common.json.JsonParser(json)) {
            parser.nextToken();
            List<Object> rows = parser.list();
            Assert.assertEquals(1, rows.size());
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) rows.get(0);
            Assert.assertEquals("products", row.get("index"));
        }
    }

    @Test
    public void testCatHealth() {
        RestTestSupport.Env env = RestTestSupport.fresh();
        RestResponse response = RestTestSupport.dispatch(env.router(), RestTestSupport.request(RestMethod.GET, "/_cat/health"));
        Assert.assertEquals(200, response.status());
        String text = new String(response.content(), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("green"));
    }
}
