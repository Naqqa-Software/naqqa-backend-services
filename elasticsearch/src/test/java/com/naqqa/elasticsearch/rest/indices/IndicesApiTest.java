package com.naqqa.elasticsearch.rest.indices;

import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.rest.RestTestSupport;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.Map;

public final class IndicesApiTest {

    @Test
    public void testCreateGetMappingAndSettings() {
        Router router = RestTestSupport.fresh().router();
        String createBody = "{\"settings\":{\"number_of_shards\":\"3\"},\"mappings\":{\"properties\":{\"name\":{\"type\":\"keyword\"}}}}";
        RestResponse createResponse = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.PUT, "/products", Map.of(), createBody));
        Assert.assertEquals(200, createResponse.status());
        Assert.assertEquals(true, RestTestSupport.asMap(createResponse).get("acknowledged"));

        RestResponse headExists = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.HEAD, "/products"));
        Assert.assertEquals(200, headExists.status());

        RestResponse getMapping = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/products/_mapping"));
        Assert.assertEquals(200, getMapping.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> mappingEntry = (Map<String, Object>) RestTestSupport.asMap(getMapping).get("products");
        @SuppressWarnings("unchecked")
        Map<String, Object> mappings = (Map<String, Object>) mappingEntry.get("mappings");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");
        Assert.assertTrue(properties.containsKey("name"));

        RestResponse getSettings = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/products/_settings"));
        Assert.assertEquals(200, getSettings.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> settingsEntry = (Map<String, Object>) RestTestSupport.asMap(getSettings).get("products");
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) settingsEntry.get("settings");
        @SuppressWarnings("unchecked")
        Map<String, Object> indexSettings = (Map<String, Object>) settings.get("index");
        Assert.assertEquals("3", indexSettings.get("number_of_shards"));
    }

    @Test
    public void testAliasCrud() {
        Router router = RestTestSupport.fresh().router();
        RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.PUT, "/products", Map.of(), "{}"));

        RestResponse putAlias = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.PUT, "/products/_alias/products-alias"));
        Assert.assertEquals(200, putAlias.status());

        RestResponse headAlias = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.HEAD, "/products/_alias/products-alias"));
        Assert.assertEquals(200, headAlias.status());

        RestResponse deleteAlias = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.DELETE, "/products/_alias/products-alias"));
        Assert.assertEquals(200, deleteAlias.status());

        RestResponse headAfterDelete = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.HEAD, "/products/_alias/products-alias"));
        Assert.assertEquals(404, headAfterDelete.status());
    }

    @Test
    public void testMissingIndexRendersEsStyleError() {
        Router router = RestTestSupport.fresh().router();
        RestResponse response = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/does-not-exist"));
        Assert.assertEquals(404, response.status());
        Map<String, Object> body = RestTestSupport.asMap(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body.get("error");
        Assert.assertEquals("index_not_found_exception", error.get("type"));
        Assert.assertEquals(404, ((Number) body.get("status")).intValue());
    }
}
