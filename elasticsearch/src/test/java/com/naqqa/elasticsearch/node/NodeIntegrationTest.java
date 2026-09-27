package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.test.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class NodeIntegrationTest {

    @SuppressWarnings("unchecked")
    private static long totalHits(NodeTestSupport.Response r) {
        Map<String, Object> hits = (Map<String, Object>) r.json().get("hits");
        return ((Number) ((Map<String, Object>) hits.get("total")).get("value")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hits(NodeTestSupport.Response r) {
        return (List<Map<String, Object>>) ((Map<String, Object>) r.json().get("hits")).get("hits");
    }

    private static void ok(NodeTestSupport.Response r) {
        assertTrue(r.status() >= 200 && r.status() < 300, "status " + r.status() + ": " + r.body());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDocumentLifecycleBulkAndQueries() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("docs")) {
            ok(es.request("PUT", "/products", "{\"settings\":{\"number_of_shards\":2},\"mappings\":{\"properties\":{"
                + "\"name\":{\"type\":\"text\"},\"category\":{\"type\":\"keyword\"},\"price\":{\"type\":\"long\"}}}}"));
            String bulk = "{\"index\":{\"_index\":\"products\",\"_id\":\"1\"}}\n{\"name\":\"red apple\",\"category\":\"fruit\",\"price\":3}\n"
                + "{\"index\":{\"_index\":\"products\",\"_id\":\"2\"}}\n{\"name\":\"green apple\",\"category\":\"fruit\",\"price\":5}\n"
                + "{\"index\":{\"_index\":\"products\",\"_id\":\"3\"}}\n{\"name\":\"carrot\",\"category\":\"vegetable\",\"price\":2}\n"
                + "{\"create\":{\"_index\":\"products\",\"_id\":\"4\"}}\n{\"name\":\"banana\",\"category\":\"fruit\",\"price\":8}\n";
            NodeTestSupport.Response bulkResponse = es.request("POST", "/_bulk?refresh=true", bulk);
            ok(bulkResponse);
            assertEquals(Boolean.FALSE, bulkResponse.json().get("errors"), bulkResponse.body());

            NodeTestSupport.Response count = es.request("GET", "/products/_count", null);
            ok(count);
            assertEquals(4L, ((Number) count.json().get("count")).longValue());

            NodeTestSupport.Response range = es.request("POST", "/products/_search",
                "{\"query\":{\"range\":{\"price\":{\"gte\":3}}},\"sort\":[{\"price\":\"desc\"}]}");
            ok(range);
            assertEquals(3L, totalHits(range));
            assertEquals("4", hits(range).get(0).get("_id"));
            assertEquals("1", hits(range).get(2).get("_id"));

            NodeTestSupport.Response bool = es.request("POST", "/products/_search",
                "{\"query\":{\"bool\":{\"must\":[{\"match\":{\"name\":\"apple\"}}],\"filter\":[{\"term\":{\"category\":\"fruit\"}}]}}}");
            ok(bool);
            assertEquals(2L, totalHits(bool));

            NodeTestSupport.Response aggs = es.request("POST", "/products/_search",
                "{\"size\":0,\"aggs\":{\"cats\":{\"terms\":{\"field\":\"category\"}},\"avg_price\":{\"avg\":{\"field\":\"price\"}}}}");
            ok(aggs);
            Map<String, Object> aggregations = (Map<String, Object>) aggs.json().get("aggregations");
            assertNotNull(aggregations);
            Map<String, Object> cats = (Map<String, Object>) aggregations.get("cats");
            List<Map<String, Object>> buckets = (List<Map<String, Object>>) cats.get("buckets");
            assertEquals("fruit", buckets.get(0).get("key"));
            assertEquals(3L, ((Number) buckets.get(0).get("doc_count")).longValue());

            ok(es.request("POST", "/products/_update/3", "{\"doc\":{\"price\":20}}"));
            NodeTestSupport.Response updated = es.request("GET", "/products/_doc/3", null);
            assertEquals(20L, ((Number) ((Map<String, Object>) updated.json().get("_source")).get("price")).longValue());

            NodeTestSupport.Response mget = es.request("POST", "/products/_mget", "{\"ids\":[\"1\",\"2\",\"missing\"]}");
            ok(mget);
            List<Map<String, Object>> docs = (List<Map<String, Object>>) mget.json().get("docs");
            assertEquals(3, docs.size());
            assertEquals(Boolean.FALSE, docs.get(2).get("found"));

            ok(es.request("DELETE", "/products/_doc/2?refresh=true", null));
            assertEquals(404, es.request("GET", "/products/_doc/2", null).status());

            NodeTestSupport.Response conflict = es.request("PUT", "/products/_create/1", "{\"name\":\"dup\"}");
            assertEquals(409, conflict.status(), conflict.body());

            NodeTestSupport.Response dbq = es.request("POST", "/products/_delete_by_query?refresh=true",
                "{\"query\":{\"term\":{\"category\":\"vegetable\"}}}");
            ok(dbq);
            assertEquals(1L, ((Number) dbq.json().get("deleted")).longValue());
            NodeTestSupport.Response after = es.request("GET", "/products/_count", null);
            assertEquals(2L, ((Number) after.json().get("count")).longValue());

            NodeTestSupport.Response auto = es.request("POST", "/autocreated/_doc?refresh=true", "{\"msg\":\"dynamic field\"}");
            assertEquals(201, auto.status(), auto.body());
            NodeTestSupport.Response autoSearch = es.request("GET", "/autocreated/_search?q=msg:dynamic", null);
            ok(autoSearch);
            assertEquals(1L, totalHits(autoSearch));

            NodeTestSupport.Response autoMapping = es.request("GET", "/autocreated/_mapping", null);
            assertTrue(autoMapping.body().contains("\"msg\""), autoMapping.body());

            ok(es.request("POST", "/_aliases", "{\"actions\":[{\"add\":{\"index\":\"products\",\"alias\":\"fruits\","
                + "\"filter\":{\"term\":{\"category\":\"fruit\"}}}}]}"));
            NodeTestSupport.Response viaFilteredAlias = es.request("GET", "/fruits/_count", null);
            ok(viaFilteredAlias);
            assertEquals(2L, ((Number) viaFilteredAlias.json().get("count")).longValue(), viaFilteredAlias.body());
            NodeTestSupport.Response aliasList = es.request("GET", "/_alias/fruits", null);
            assertTrue(aliasList.body().contains("filter"), aliasList.body());

            NodeTestSupport.Response multi = es.request("GET", "/products,autocreated/_search", null);
            ok(multi);
            assertEquals(3L, totalHits(multi));

            NodeTestSupport.Response missing = es.request("GET", "/nope/_search", null);
            assertEquals(404, missing.status(), missing.body());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAdminClusterAndCatApis() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("admin")) {
            ok(es.request("PUT", "/logs-1", "{\"aliases\":{\"logs\":{\"is_write_index\":true}}}"));
            ok(es.request("PUT", "/logs/_doc/a?refresh=true", "{\"level\":\"info\"}"));
            NodeTestSupport.Response viaAlias = es.request("GET", "/logs/_search", null);
            ok(viaAlias);
            assertEquals(1L, totalHits(viaAlias));
            assertEquals("logs-1", hits(viaAlias).get(0).get("_index"));

            NodeTestSupport.Response health = es.request("GET", "/_cluster/health?wait_for_status=green&timeout=10s", null);
            ok(health);
            assertEquals("green", health.json().get("status"), health.body());
            assertEquals("test-cluster-admin", health.json().get("cluster_name"));

            NodeTestSupport.Response mapping = es.request("GET", "/logs-1/_mapping", null);
            ok(mapping);
            assertTrue(mapping.body().contains("logs-1"), mapping.body());

            ok(es.request("PUT", "/logs-1/_mapping", "{\"properties\":{\"host\":{\"type\":\"keyword\"}}}"));
            NodeTestSupport.Response mapping2 = es.request("GET", "/logs-1/_mapping", null);
            assertTrue(mapping2.body().contains("host"), mapping2.body());

            ok(es.request("PUT", "/logs-1/_settings", "{\"index\":{\"refresh_interval\":\"5s\"}}"));
            NodeTestSupport.Response settings = es.request("GET", "/logs-1/_settings", null);
            assertTrue(settings.body().contains("5s"), settings.body());

            NodeTestSupport.Response rollover = es.request("POST", "/logs/_rollover", null);
            ok(rollover);
            assertEquals("logs-000002", rollover.json().get("new_index"), rollover.body());
            ok(es.request("PUT", "/logs/_doc/b?refresh=true", "{\"level\":\"warn\"}"));
            assertEquals(200, es.request("GET", "/logs-000002/_doc/b", null).status());

            NodeTestSupport.Response state = es.request("GET", "/_cluster/state", null);
            ok(state);
            assertTrue(state.body().contains("logs-000002"), state.body());

            NodeTestSupport.Response catIndices = es.request("GET", "/_cat/indices?v", null);
            ok(catIndices);
            assertTrue(catIndices.body().contains("logs-1") && catIndices.body().contains("logs-000002"), catIndices.body());

            NodeTestSupport.Response catHealth = es.request("GET", "/_cat/health", null);
            ok(catHealth);
            assertTrue(catHealth.body().contains("green"), catHealth.body());

            NodeTestSupport.Response nodesStats = es.request("GET", "/_nodes/stats", null);
            ok(nodesStats);
            Map<String, Object> nodes = (Map<String, Object>) nodesStats.json().get("nodes");
            assertEquals(1, nodes.size());
            Map<String, Object> nodeStats = (Map<String, Object>) nodes.values().iterator().next();
            Map<String, Object> indices = (Map<String, Object>) nodeStats.get("indices");
            Map<String, Object> indexing = (Map<String, Object>) indices.get("indexing");
            assertTrue(((Number) indexing.get("index_total")).longValue() >= 2, nodesStats.body());

            NodeTestSupport.Response stats = es.request("GET", "/logs-1/_stats", null);
            ok(stats);

            ok(es.request("PUT", "/_index_template/tmpl", "{\"index_patterns\":[\"tmpl-*\"],\"template\":{\"settings\":"
                + "{\"number_of_shards\":3},\"mappings\":{\"properties\":{\"k\":{\"type\":\"keyword\"}}}}}"));
            ok(es.request("PUT", "/tmpl-a", null));
            NodeTestSupport.Response tmplSettings = es.request("GET", "/tmpl-a/_settings", null);
            assertTrue(tmplSettings.body().contains("\"number_of_shards\":\"3\""), tmplSettings.body());

            ok(es.request("POST", "/logs-1/_close", null));
            NodeTestSupport.Response closedSearch = es.request("GET", "/logs-1/_doc/a", null);
            assertTrue(closedSearch.status() >= 400, closedSearch.body());
            ok(es.request("POST", "/logs-1/_open", null));
            assertEquals(200, es.request("GET", "/logs-1/_doc/a", null).status());

            ok(es.request("DELETE", "/tmpl-a", null));
            assertEquals(404, es.request("HEAD", "/tmpl-a", null).status());
            assertEquals(200, es.request("HEAD", "/logs-1", null).status());

            NodeTestSupport.Response tasks = es.request("GET", "/_tasks", null);
            ok(tasks);
            NodeTestSupport.Response putCluster = es.request("PUT", "/_cluster/settings",
                "{\"persistent\":{\"cluster.routing.allocation.enable\":\"all\"}}");
            ok(putCluster);
            assertTrue(es.request("GET", "/_cluster/settings", null).body().contains("allocation"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testRestartRecoversIndicesAndDocuments() throws Exception {
        Path dir = Files.createTempDirectory("node-restart");
        NodeTestSupport first = new NodeTestSupport("restart", Settings.EMPTY, dir);
        try {
            ok(first.request("PUT", "/persist", "{\"mappings\":{\"properties\":{\"v\":{\"type\":\"keyword\"}}},\"aliases\":{\"p\":{}}}"));
            ok(first.request("PUT", "/persist/_doc/1", "{\"v\":\"one\"}"));
            ok(first.request("PUT", "/persist/_doc/2?refresh=true", "{\"v\":\"two\"}"));
        } catch (Exception | AssertionError e) {
            first.close();
            throw e;
        }
        first.closeNodeOnly();
        try (NodeTestSupport es = new NodeTestSupport("restart", Settings.EMPTY, dir)) {
            NodeTestSupport.Response health = es.request("GET", "/_cluster/health/persist?wait_for_status=green&timeout=20s", null);
            assertEquals("green", health.json().get("status"), health.body());
            NodeTestSupport.Response got = es.request("GET", "/persist/_doc/1", null);
            assertEquals(200, got.status(), got.body());
            assertEquals("one", ((Map<String, Object>) got.json().get("_source")).get("v"));
            ok(es.request("POST", "/persist/_refresh", null));
            NodeTestSupport.Response search = es.request("GET", "/p/_search?q=v:two", null);
            ok(search);
            assertEquals(1L, totalHits(search));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testIngestPipelineAndSnapshotRestore() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("snap")) {
            ok(es.request("PUT", "/_ingest/pipeline/upper", "{\"processors\":[{\"uppercase\":{\"field\":\"name\"}},"
                + "{\"set\":{\"field\":\"ingested\",\"value\":true}}]}"));
            ok(es.request("PUT", "/src", "{\"settings\":{\"index\":{\"default_pipeline\":\"upper\"}}}"));
            ok(es.request("PUT", "/src/_doc/1?refresh=true", "{\"name\":\"alice\"}"));
            NodeTestSupport.Response got = es.request("GET", "/src/_doc/1", null);
            Map<String, Object> source = (Map<String, Object>) got.json().get("_source");
            assertEquals("ALICE", source.get("name"), got.body());
            assertEquals(Boolean.TRUE, source.get("ingested"));

            ok(es.request("PUT", "/_snapshot/backup", "{\"type\":\"fs\",\"settings\":{\"location\":\"backup\"}}"));
            NodeTestSupport.Response snap = es.request("PUT", "/_snapshot/backup/snap1?wait_for_completion=true", "{\"indices\":\"src\"}");
            ok(snap);
            assertEquals("SUCCESS", ((Map<String, Object>) snap.json().get("snapshot")).get("state"), snap.body());
            NodeTestSupport.Response catSnaps = es.request("GET", "/_cat/snapshots/backup", null);
            assertTrue(catSnaps.body().contains("snap1"), catSnaps.body());

            NodeTestSupport.Response restore = es.request("POST", "/_snapshot/backup/snap1/_restore?wait_for_completion=true",
                "{\"indices\":\"src\",\"rename_pattern\":\"src\",\"rename_replacement\":\"restored\"}");
            ok(restore);
            NodeTestSupport.Response restored = es.request("GET", "/restored/_doc/1", null);
            assertEquals(200, restored.status(), restored.body());
            assertEquals("ALICE", ((Map<String, Object>) restored.json().get("_source")).get("name"));
        }
    }

    @Test
    public void testSecurityRequiresAuthentication() throws Exception {
        Settings secure = Settings.builder().put("xpack.security.enabled", true).put("bootstrap.password", "changeme").build();
        try (NodeTestSupport es = new NodeTestSupport("secure", secure, Files.createTempDirectory("node-secure"))) {
            NodeTestSupport.Response anonymous = es.request("GET", "/", null);
            assertEquals(401, anonymous.status(), anonymous.body());
            es.basicAuth("elastic", "wrong");
            assertEquals(401, es.request("GET", "/", null).status());
            es.basicAuth("elastic", "changeme");
            assertEquals(200, es.request("GET", "/", null).status());
            NodeTestSupport.Response who = es.request("GET", "/_security/_authenticate", null);
            ok(who);
            assertEquals("elastic", who.json().get("username"));
            ok(es.request("PUT", "/_security/user/reader", "{\"password\":\"readerpass\",\"roles\":[\"viewer\"]}"));
            ok(es.request("PUT", "/secured/_doc/1?refresh=true", "{\"a\":1}"));
            es.basicAuth("reader", "readerpass");
            ok(es.request("GET", "/secured/_search", null));
            NodeTestSupport.Response denied = es.request("PUT", "/secured/_doc/2", "{\"a\":2}");
            assertEquals(403, denied.status(), denied.body());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testByQueryScrollPitAndAdvancedSearch() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("advanced")) {
            ok(es.request("PUT", "/events", "{\"mappings\":{\"properties\":{\"ts\":{\"type\":\"date\"},"
                + "\"n\":{\"type\":\"integer\"},\"score\":{\"type\":\"double\"},\"kind\":{\"type\":\"keyword\"},"
                + "\"msg\":{\"type\":\"text\"}}}}"));
            StringBuilder bulk = new StringBuilder();
            for (int i = 1; i <= 25; i++) {
                bulk.append("{\"index\":{\"_id\":\"").append(i).append("\"}}\n");
                bulk.append("{\"ts\":\"2024-01-").append(String.format("%02d", i)).append("T00:00:00Z\",\"n\":").append(i)
                    .append(",\"score\":").append(i / 2.0).append(",\"kind\":\"").append(i % 2 == 0 ? "even" : "odd")
                    .append("\",\"msg\":\"event number ").append(i).append("\"}\n");
            }
            NodeTestSupport.Response bulkResponse = es.request("POST", "/events/_bulk?refresh=true", bulk.toString());
            ok(bulkResponse);
            assertEquals(Boolean.FALSE, bulkResponse.json().get("errors"), bulkResponse.body());

            NodeTestSupport.Response dateRange = es.request("POST", "/events/_search",
                "{\"query\":{\"range\":{\"ts\":{\"gte\":\"2024-01-10\",\"lt\":\"2024-01-15\"}}}}");
            ok(dateRange);
            assertEquals(5L, totalHits(dateRange), dateRange.body());

            NodeTestSupport.Response doubleRange = es.request("POST", "/events/_count",
                "{\"query\":{\"range\":{\"score\":{\"gt\":10.0}}}}");
            ok(doubleRange);
            assertEquals(5L, ((Number) doubleRange.json().get("count")).longValue(), doubleRange.body());

            NodeTestSupport.Response termNumeric = es.request("POST", "/events/_search", "{\"query\":{\"term\":{\"n\":7}}}");
            assertEquals(1L, totalHits(termNumeric), termNumeric.body());

            NodeTestSupport.Response scroll1 = es.request("POST", "/events/_search?scroll=1m",
                "{\"size\":10,\"query\":{\"match_all\":{}}}");
            ok(scroll1);
            String scrollId = String.valueOf(scroll1.json().get("_scroll_id"));
            int seen = hits(scroll1).size();
            while (true) {
                NodeTestSupport.Response next = es.request("POST", "/_search/scroll",
                    "{\"scroll\":\"1m\",\"scroll_id\":\"" + scrollId + "\"}");
                ok(next);
                int page = hits(next).size();
                if (page == 0) {
                    break;
                }
                seen += page;
            }
            assertEquals(25, seen);
            ok(es.request("DELETE", "/_search/scroll", "{\"scroll_id\":\"" + scrollId + "\"}"));

            NodeTestSupport.Response pit = es.request("POST", "/events/_pit?keep_alive=1m", null);
            ok(pit);
            String pitId = String.valueOf(pit.json().get("id"));
            ok(es.request("PUT", "/events/_doc/99?refresh=true", "{\"n\":99,\"kind\":\"odd\"}"));
            NodeTestSupport.Response pitSearch = es.request("POST", "/_search",
                "{\"pit\":{\"id\":\"" + pitId + "\"},\"query\":{\"match_all\":{}}}");
            ok(pitSearch);
            assertEquals(25L, totalHits(pitSearch), pitSearch.body());
            ok(es.request("DELETE", "/_pit", "{\"id\":\"" + pitId + "\"}"));

            NodeTestSupport.Response msearch = es.request("POST", "/_msearch",
                "{\"index\":\"events\"}\n{\"query\":{\"term\":{\"kind\":\"even\"}}}\n{\"index\":\"missing-index\"}\n{}\n");
            ok(msearch);
            List<Map<String, Object>> responses = (List<Map<String, Object>>) msearch.json().get("responses");
            assertEquals(2, responses.size());
            assertEquals(12L, ((Number) ((Map<String, Object>) ((Map<String, Object>) responses.get(0).get("hits")).get("total"))
                .get("value")).longValue());
            assertEquals(404, ((Number) responses.get(1).get("status")).intValue());

            NodeTestSupport.Response explain = es.request("POST", "/events/_explain/3", "{\"query\":{\"match\":{\"msg\":\"event\"}}}");
            ok(explain);
            assertEquals(Boolean.TRUE, explain.json().get("matched"), explain.body());

            NodeTestSupport.Response caps = es.request("GET", "/events/_field_caps?fields=*", null);
            ok(caps);
            assertTrue(caps.body().contains("\"kind\"") && caps.body().contains("keyword"), caps.body());

            NodeTestSupport.Response ubq = es.request("POST", "/events/_update_by_query?refresh=true",
                "{\"query\":{\"range\":{\"n\":{\"lte\":5}}},\"script\":{\"source\":\"ctx._source.kind = 'early'\"}}");
            ok(ubq);
            assertEquals(5L, ((Number) ubq.json().get("updated")).longValue(), ubq.body());
            NodeTestSupport.Response early = es.request("POST", "/events/_count", "{\"query\":{\"term\":{\"kind\":\"early\"}}}");
            assertEquals(5L, ((Number) early.json().get("count")).longValue(), early.body());

            NodeTestSupport.Response dbq = es.request("POST", "/events/_delete_by_query?refresh=true",
                "{\"query\":{\"range\":{\"n\":{\"gt\":20}}}}");
            ok(dbq);
            assertEquals(6L, ((Number) dbq.json().get("deleted")).longValue(), dbq.body());

            ok(es.request("PUT", "/events-copy", null));
            NodeTestSupport.Response reindex = es.request("POST", "/_reindex?refresh=true",
                "{\"source\":{\"index\":\"events\",\"query\":{\"term\":{\"kind\":\"early\"}}},\"dest\":{\"index\":\"events-copy\"}}");
            ok(reindex);
            ok(es.request("POST", "/events-copy/_refresh", null));
            NodeTestSupport.Response copyCount = es.request("GET", "/events-copy/_count", null);
            assertEquals(5L, ((Number) copyCount.json().get("count")).longValue(), copyCount.body() + " / " + reindex.body());

            NodeTestSupport.Response catShards = es.request("GET", "/_cat/shards/events", null);
            ok(catShards);
            assertTrue(catShards.body().contains("STARTED"), catShards.body());
        }
    }

    @Test
    public void testLifecyclePolicyDrivesRealRollover() throws Exception {
        Settings fastIlm = Settings.builder().put("indices.lifecycle.poll_interval", "200ms").build();
        try (NodeTestSupport es = new NodeTestSupport("ilm", fastIlm, Files.createTempDirectory("node-ilm"))) {
            ok(es.request("PUT", "/_ilm/policy/roll", "{\"policy\":{\"phases\":{\"hot\":{\"actions\":{"
                + "\"rollover\":{\"max_docs\":1}}}}}}"));
            ok(es.request("PUT", "/app-000001", "{\"settings\":{\"index.lifecycle.name\":\"roll\","
                + "\"index.lifecycle.rollover_alias\":\"app\"},\"aliases\":{\"app\":{\"is_write_index\":true}}}"));
            ok(es.request("PUT", "/app/_doc/1?refresh=true", "{\"x\":1}"));
            boolean rolled = false;
            for (int i = 0; i < 100 && !rolled; i++) {
                Thread.sleep(100);
                rolled = es.request("HEAD", "/app-000002", null).status() == 200;
            }
            assertTrue(rolled, es.request("GET", "/app-000001/_ilm/explain", null).body());
            ok(es.request("PUT", "/app/_doc/2?refresh=true", "{\"x\":2}"));
            assertEquals(200, es.request("GET", "/app-000002/_doc/2", null).status());
        }
    }

    @Test
    public void testBootstrapLoadsYamlAndCommandLineOverrides() throws Exception {
        Path dir = Files.createTempDirectory("bootstrap-conf");
        try {
            Path yml = dir.resolve("elasticsearch.yml");
            Files.writeString(yml, "cluster.name: from-file\nnode.name: file-node\nhttp.port: 9555\n");
            Settings settings = Bootstrap.loadSettings(new String[] {yml.toString(), "-Ehttp.port=0", "-Enode.name=cli-node"});
            assertEquals("from-file", settings.get("cluster.name"));
            assertEquals("cli-node", settings.get("node.name"));
            assertEquals("0", settings.get("http.port"));
            assertEquals(dir.toAbsolutePath().toString(), settings.get("path.conf"));
        } finally {
            NodeTestSupport.deleteRecursively(dir);
        }
    }
}
