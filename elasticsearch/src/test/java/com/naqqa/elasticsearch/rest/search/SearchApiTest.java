package com.naqqa.elasticsearch.rest.search;

import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.rest.RestTestSupport;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class SearchApiTest {

    @Test
    public void testSearchPassthrough() {
        Router router = RestTestSupport.fresh().router();
        RestResponse response = RestTestSupport.dispatch(router,
            RestTestSupport.request(RestMethod.POST, "/products/_search", Map.of(), "{\"query\":{\"match_all\":{}}}"));
        Assert.assertEquals(200, response.status());
        Map<String, Object> body = RestTestSupport.asMap(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> hits = (Map<String, Object>) body.get("hits");
        @SuppressWarnings("unchecked")
        Map<String, Object> total = (Map<String, Object>) hits.get("total");
        Assert.assertEquals(0, ((Number) total.get("value")).intValue());
    }

    @Test
    public void testMultiSearchNdjson() {
        Router router = RestTestSupport.fresh().router();
        String ndjson = String.join("\n",
            "{\"index\":\"products\"}",
            "{\"query\":{\"match_all\":{}}}",
            "{}",
            "{\"query\":{\"term\":{\"name\":\"widget\"}}}") + "\n";
        RestResponse response = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.POST, "/_msearch", Map.of(), ndjson));
        Assert.assertEquals(200, response.status());
        Map<String, Object> body = RestTestSupport.asMap(response);
        @SuppressWarnings("unchecked")
        List<Object> responses = (List<Object>) body.get("responses");
        Assert.assertEquals(2, responses.size());
    }

    @Test
    public void testCountAndValidateQuery() {
        Router router = RestTestSupport.fresh().router();
        RestResponse count = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/products/_count"));
        Assert.assertEquals(200, count.status());
        Assert.assertEquals(0, ((Number) RestTestSupport.asMap(count).get("count")).intValue());

        RestResponse validate = RestTestSupport.dispatch(router,
            RestTestSupport.request(RestMethod.POST, "/products/_validate/query", Map.of(), "{\"query\":{\"match_all\":{}}}"));
        Assert.assertEquals(200, validate.status());
        Assert.assertEquals(true, RestTestSupport.asMap(validate).get("valid"));
    }
}
