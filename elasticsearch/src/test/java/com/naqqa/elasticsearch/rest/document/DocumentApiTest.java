package com.naqqa.elasticsearch.rest.document;

import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.rest.RestTestSupport;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class DocumentApiTest {

    @Test
    public void testIndexGetDeleteRoundTrip() {
        Router router = RestTestSupport.fresh().router();

        RestResponse indexResponse = RestTestSupport.dispatch(router,
            RestTestSupport.request(RestMethod.PUT, "/products/_doc/1", Map.of(), "{\"name\":\"widget\",\"price\":9}"));
        Assert.assertEquals(201, indexResponse.status());
        Map<String, Object> indexBody = RestTestSupport.asMap(indexResponse);
        Assert.assertEquals("created", indexBody.get("result"));
        Assert.assertEquals("products", indexBody.get("_index"));
        Assert.assertEquals("1", indexBody.get("_id"));

        RestResponse getResponse = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/products/_doc/1"));
        Assert.assertEquals(200, getResponse.status());
        Map<String, Object> getBody = RestTestSupport.asMap(getResponse);
        Assert.assertEquals(true, getBody.get("found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) getBody.get("_source");
        Assert.assertEquals("widget", source.get("name"));

        RestResponse deleteResponse = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.DELETE, "/products/_doc/1"));
        Assert.assertEquals(200, deleteResponse.status());
        Assert.assertEquals("deleted", RestTestSupport.asMap(deleteResponse).get("result"));

        RestResponse getAfterDelete = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/products/_doc/1"));
        Assert.assertEquals(404, getAfterDelete.status());
        Assert.assertEquals(false, RestTestSupport.asMap(getAfterDelete).get("found"));
    }

    @Test
    public void testBulkMixedActionsIncludingError() {
        Router router = RestTestSupport.fresh().router();
        String ndjson = String.join("\n",
            "{\"index\":{\"_index\":\"products\",\"_id\":\"1\"}}",
            "{\"name\":\"widget\"}",
            "{\"update\":{\"_index\":\"products\",\"_id\":\"1\"}}",
            "{\"doc\":{\"price\":5}}",
            "{\"delete\":{\"_index\":\"products\",\"_id\":\"1\"}}",
            "{\"update\":{\"_index\":\"products\",\"_id\":\"missing-doc\"}}",
            "{\"doc\":{\"price\":1}}") + "\n";
        RestResponse response = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.POST, "/_bulk", Map.of(), ndjson));
        Assert.assertEquals(200, response.status());
        Map<String, Object> body = RestTestSupport.asMap(response);
        Assert.assertEquals(true, body.get("errors"));
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) body.get("items");
        Assert.assertEquals(4, items.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> indexItem = (Map<String, Object>) ((Map<String, Object>) items.get(0)).get("index");
        Assert.assertEquals(201, ((Number) indexItem.get("status")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> deleteItem = (Map<String, Object>) ((Map<String, Object>) items.get(2)).get("delete");
        Assert.assertEquals("deleted", deleteItem.get("result"));

        @SuppressWarnings("unchecked")
        Map<String, Object> failedUpdate = (Map<String, Object>) ((Map<String, Object>) items.get(3)).get("update");
        Assert.assertEquals(404, ((Number) failedUpdate.get("status")).intValue());
        Assert.assertNotNull(failedUpdate.get("error"));
    }

    @Test
    public void testBadJsonBodyRendersEsStyleError() {
        Router router = RestTestSupport.fresh().router();
        RestResponse response = RestTestSupport.dispatch(router,
            RestTestSupport.request(RestMethod.PUT, "/products/_doc/1", Map.of(), "{ not valid json"));
        Assert.assertEquals(400, response.status());
        Map<String, Object> body = RestTestSupport.asMap(response);
        Assert.assertEquals(400, ((Number) body.get("status")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        Assert.assertEquals("illegal_argument_exception", error.get("type"));
        Assert.assertNotNull(error.get("root_cause"));
    }

    @Test
    public void testMgetAndVersionConflictOnCreate() {
        Router router = RestTestSupport.fresh().router();
        RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.PUT, "/products/_create/1", Map.of(), "{\"name\":\"widget\"}"));

        RestResponse conflict = RestTestSupport.dispatch(router,
            RestTestSupport.request(RestMethod.PUT, "/products/_create/1", Map.of(), "{\"name\":\"widget2\"}"));
        Assert.assertEquals(409, conflict.status());

        String mgetBody = "{\"docs\":[{\"_index\":\"products\",\"_id\":\"1\"},{\"_index\":\"products\",\"_id\":\"absent\"}]}";
        RestResponse mgetResponse = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.POST, "/_mget", Map.of(), mgetBody));
        Assert.assertEquals(200, mgetResponse.status());
        @SuppressWarnings("unchecked")
        List<Object> docs = (List<Object>) RestTestSupport.asMap(mgetResponse).get("docs");
        Assert.assertEquals(2, docs.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) docs.get(0);
        Assert.assertEquals(true, first.get("found"));
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) docs.get(1);
        Assert.assertEquals(false, second.get("found"));
    }
}
