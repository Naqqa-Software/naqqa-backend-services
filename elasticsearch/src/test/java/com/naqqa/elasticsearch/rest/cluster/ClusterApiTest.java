package com.naqqa.elasticsearch.rest.cluster;

import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.rest.RestTestSupport;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.Map;

public final class ClusterApiTest {

    @Test
    public void testClusterHealth() {
        Router router = RestTestSupport.fresh().router();
        RestResponse response = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/_cluster/health"));
        Assert.assertEquals(200, response.status());
        Map<String, Object> body = RestTestSupport.asMap(response);
        Assert.assertEquals("green", body.get("status"));
        Assert.assertEquals("naqqa-cluster", body.get("cluster_name"));
    }

    @Test
    public void testClusterStateFilteredByMetrics() {
        Router router = RestTestSupport.fresh().router();
        RestResponse response = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/_cluster/state/nodes"));
        Assert.assertEquals(200, response.status());
        Map<String, Object> body = RestTestSupport.asMap(response);
        Assert.assertTrue(body.containsKey("nodes"));
        Assert.assertFalse(body.containsKey("routing_table"));
    }

    @Test
    public void testClusterSettingsPutAndGet() {
        Router router = RestTestSupport.fresh().router();
        RestResponse put = RestTestSupport.dispatch(router,
            RestTestSupport.request(RestMethod.PUT, "/_cluster/settings", Map.of(), "{\"persistent\":{\"cluster.routing.allocation.enable\":\"all\"}}"));
        Assert.assertEquals(200, put.status());

        RestResponse get = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/_cluster/settings"));
        Assert.assertEquals(200, get.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> persistent = (Map<String, Object>) RestTestSupport.asMap(get).get("persistent");
        Assert.assertEquals("all", persistent.get("cluster.routing.allocation.enable"));
    }

    @Test
    public void testGetUnknownTaskRendersNotFound() {
        Router router = RestTestSupport.fresh().router();
        RestResponse response = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/_tasks/abc:1"));
        Assert.assertEquals(404, response.status());
    }
}
