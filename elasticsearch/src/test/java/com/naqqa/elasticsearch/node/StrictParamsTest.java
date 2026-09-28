package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.test.Test;

import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class StrictParamsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(NodeTestSupport.Response response) {
        return (Map<String, Object>) response.json().get("error");
    }

    private static void ok(NodeTestSupport.Response r) {
        assertTrue(r.status() >= 200 && r.status() < 300, "status " + r.status() + ": " + r.body());
    }

    @Test
    public void testUnknownSearchParamSuggestsClosestMatch() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-search")) {
            ok(es.request("PUT", "/products", null));

            NodeTestSupport.Response response = es.request("GET", "/products/_search?sise=10", null);
            assertEquals(400, response.status(), response.body());
            assertEquals(400, ((Number) response.json().get("status")).intValue());
            Map<String, Object> error = error(response);
            assertEquals("illegal_argument_exception", error.get("type"));
            String reason = String.valueOf(error.get("reason"));
            assertEquals("request [/products/_search] contains unrecognized parameter: [sise] -> did you mean [size]?", reason);
        }
    }

    @Test
    public void testUnknownRefreshParamOnIndexSuggestsRefresh() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-doc")) {
            NodeTestSupport.Response response = es.request("PUT", "/products/_doc/1?refesh=true", "{\"name\":\"widget\"}");
            assertEquals(400, response.status(), response.body());
            Map<String, Object> error = error(response);
            assertEquals("illegal_argument_exception", error.get("type"));
            String reason = String.valueOf(error.get("reason"));
            assertTrue(reason.contains("[refesh]"), reason);
            assertTrue(reason.contains("did you mean [refresh]?"), reason);
        }
    }

    @Test
    public void testMultipleUnknownParamsUsePluralMessage() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-plural")) {
            ok(es.request("PUT", "/products", null));

            NodeTestSupport.Response response = es.request("GET", "/products/_search?foo=1&bar=2", null);
            assertEquals(400, response.status(), response.body());
            Map<String, Object> error = error(response);
            String reason = String.valueOf(error.get("reason"));
            assertTrue(reason.contains("unrecognized parameters:"), reason);
            assertTrue(reason.contains("[foo]"), reason);
            assertTrue(reason.contains("[bar]"), reason);
        }
    }

    @Test
    public void testCompletelyUnrelatedParamGetsNoSuggestion() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-nosuggest")) {
            ok(es.request("PUT", "/products", null));

            NodeTestSupport.Response response = es.request("GET", "/products/_search?zzzqqqxxxnotaparam=1", null);
            assertEquals(400, response.status(), response.body());
            Map<String, Object> error = error(response);
            String reason = String.valueOf(error.get("reason"));
            assertTrue(reason.contains("[zzzqqqxxxnotaparam]"), reason);
            assertFalse(reason.contains("did you mean"), reason);
        }
    }

    @Test
    public void testGlobalParamsAreAlwaysAccepted() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-global")) {
            ok(es.request("PUT", "/products", null));

            NodeTestSupport.Response response = es.request("GET",
                "/products/_search?pretty&human&error_trace&filter_path=hits.total", null);
            assertEquals(200, response.status(), response.body());
        }
    }

    @Test
    public void testCatIndicesWithVerboseHeadersAndSortAccepted() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-cat")) {
            ok(es.request("PUT", "/products", null));

            NodeTestSupport.Response response = es.request("GET", "/_cat/indices?v&h=index&s=index", null);
            assertEquals(200, response.status(), response.body());
            assertTrue(response.body().contains("products"), response.body());
        }
    }

    @Test
    public void testCatHelpIsSupported() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-cat-help")) {
            NodeTestSupport.Response response = es.request("GET", "/_cat/indices?help", null);
            assertEquals(200, response.status(), response.body());
        }
    }

    @Test
    public void testKnownValidParamCombinationsStillWork() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("strict-valid")) {
            ok(es.request("PUT", "/products", "{\"settings\":{\"number_of_shards\":1}}"));
            ok(es.request("PUT", "/products/_doc/1?refresh=true&version=1&version_type=external", "{\"name\":\"widget\"}"));

            NodeTestSupport.Response search = es.request("GET",
                "/products/_search?q=name:widget&size=5&from=0&timeout=10s&track_total_hits=true", null);
            ok(search);

            NodeTestSupport.Response bulk = es.request("POST", "/_bulk?refresh=true",
                "{\"index\":{\"_index\":\"products\",\"_id\":\"2\"}}\n{\"name\":\"gadget\"}\n");
            ok(bulk);

            NodeTestSupport.Response health = es.request("GET", "/_cluster/health?wait_for_status=green&timeout=10s", null);
            ok(health);

            NodeTestSupport.Response deleteByQuery = es.request("POST", "/products/_delete_by_query?refresh=true",
                "{\"query\":{\"match\":{\"name\":\"gadget\"}}}");
            ok(deleteByQuery);
        }
    }
}
