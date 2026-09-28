package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.common.lifecycle.Lifecycle;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class NodeSmokeTest {

    @Test
    @SuppressWarnings("unchecked")
    public void testCreateIndexGetAndSearchOverRealHttp() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("smoke")) {
            NodeTestSupport.Response root = es.request("GET", "/", null);
            assertEquals(200, root.status());
            assertEquals("smoke", root.json().get("name"));

            NodeTestSupport.Response created = es.request("PUT", "/myindex",
                "{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"},\"tag\":{\"type\":\"keyword\"}}}}");
            assertEquals(200, created.status(), created.body());
            assertEquals(Boolean.TRUE, created.json().get("acknowledged"));
            assertEquals(Boolean.TRUE, created.json().get("shards_acknowledged"));

            NodeTestSupport.Response indexed = es.request("PUT", "/myindex/_doc/1",
                "{\"title\":\"hello elasticsearch world\",\"tag\":\"greeting\"}");
            assertTrue(indexed.status() == 201 || indexed.status() == 200, indexed.body());
            assertEquals("created", indexed.json().get("result"));
            assertEquals("1", indexed.json().get("_id"));

            NodeTestSupport.Response got = es.request("GET", "/myindex/_doc/1", null);
            assertEquals(200, got.status(), got.body());
            Map<String, Object> getBody = got.json();
            assertEquals(Boolean.TRUE, getBody.get("found"));
            Map<String, Object> source = (Map<String, Object>) getBody.get("_source");
            assertEquals("hello elasticsearch world", source.get("title"));
            assertEquals("greeting", source.get("tag"));

            NodeTestSupport.Response refreshed = es.request("POST", "/myindex/_refresh", null);
            assertEquals(200, refreshed.status(), refreshed.body());

            NodeTestSupport.Response searched = es.request("POST", "/myindex/_search",
                "{\"query\":{\"match\":{\"title\":\"elasticsearch\"}}}");
            assertEquals(200, searched.status(), searched.body());
            Map<String, Object> hits = (Map<String, Object>) searched.json().get("hits");
            Map<String, Object> total = (Map<String, Object>) hits.get("total");
            assertEquals(1L, ((Number) total.get("value")).longValue(), searched.body());
            List<Object> hitList = (List<Object>) hits.get("hits");
            assertEquals(1, hitList.size(), searched.body());
            Map<String, Object> hit = (Map<String, Object>) hitList.get(0);
            assertEquals("1", hit.get("_id"));
            assertEquals("myindex", hit.get("_index"));
            Map<String, Object> hitSource = (Map<String, Object>) hit.get("_source");
            assertEquals("hello elasticsearch world", hitSource.get("title"));

            NodeTestSupport.Response miss = es.request("POST", "/myindex/_search", "{\"query\":{\"match\":{\"title\":\"nomatch\"}}}");
            assertEquals(200, miss.status());
            Map<String, Object> missTotal = (Map<String, Object>) ((Map<String, Object>) miss.json().get("hits")).get("total");
            assertEquals(0L, ((Number) missTotal.get("value")).longValue());

            es.closeNodeOnly();
            assertEquals(Lifecycle.State.CLOSED, es.node.lifecycleState());
        }
    }
}
