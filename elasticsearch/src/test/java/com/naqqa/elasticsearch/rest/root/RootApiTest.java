package com.naqqa.elasticsearch.rest.root;

import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.rest.RestTestSupport;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.Map;

public final class RootApiTest {

    @Test
    public void testRootReturnsClusterInfo() {
        Router router = RestTestSupport.fresh().router();
        RestResponse response = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.GET, "/"));
        Assert.assertEquals(200, response.status());
        Map<String, Object> body = RestTestSupport.asMap(response);
        Assert.assertEquals("naqqa-cluster", body.get("cluster_name"));
        Assert.assertEquals("You Know, for Search", body.get("tagline"));
        @SuppressWarnings("unchecked")
        Map<String, Object> version = (Map<String, Object>) body.get("version");
        Assert.assertNotNull(version.get("number"));
    }

    @Test
    public void testHeadRootReturnsNoBody() {
        Router router = RestTestSupport.fresh().router();
        RestResponse response = RestTestSupport.dispatch(router, RestTestSupport.request(RestMethod.HEAD, "/"));
        Assert.assertEquals(200, response.status());
    }

    @Test
    public void testUnknownRouteRendersNotFound() {
        Router router = RestTestSupport.fresh().router();
        RestResponse response = RestTestSupport.dispatch(router,
            RestTestSupport.request(RestMethod.GET, "/_totally/unknown/deeply/nested/path"));
        Assert.assertEquals(400, response.status());
    }
}
