package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;
import static com.naqqa.elasticsearch.test.Assert.fail;

public class MultiNodeTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    record Response(int status, String body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> json() {
            return (Map<String, Object>) JsonValue.parse(body.getBytes(StandardCharsets.UTF_8)).toJava();
        }
    }

    private static final class ClusterMember {
        final String name;
        final Path home;
        final Settings baseSettings;
        Node node;
        int lastPort;

        ClusterMember(String name, Path home, Settings baseSettings) {
            this.name = name;
            this.home = home;
            this.baseSettings = baseSettings;
        }

        Settings settings(int transportPort) {
            return Settings.builder()
                .put(baseSettings)
                .put("node.name", name)
                .put("path.data", home.resolve("data").toString())
                .put("path.logs", home.resolve("logs").toString())
                .put("path.conf", home.resolve("config").toString())
                .put("path.repo", home.getParent().resolve("shared-repo").toString())
                .put("http.port", 0)
                .put("transport.port", transportPort)
                .build();
        }

        void start(int transportPort) {
            this.lastPort = transportPort;
            node = new Node(settings(transportPort));
            node.start();
        }

        void stop() {
            if (node != null) {
                node.close();
                node = null;
            }
        }

        String url() {
            return "http://127.0.0.1:" + node.httpAddress().getPort();
        }

        ClusterState state() {
            return node.clusterStateManager().state();
        }

        String id() {
            return node.nodeInfo().id();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static Response request(ClusterMember member, String method, String path, String body) throws Exception {
        return request(member, method, path, body, null, null);
    }

    private static Response request(ClusterMember member, String method, String path, String body, String user, String password)
        throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(member.url() + path)).timeout(Duration.ofSeconds(90));
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        if (body != null) {
            builder.header("Content-Type", path.contains("_bulk") ? "application/x-ndjson" : "application/json");
        }
        if (user != null) {
            String credentials = java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + credentials);
        }
        builder.method(method, publisher);
        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }

    private static void ok(Response r) {
        assertTrue(r.status() >= 200 && r.status() < 300, "status " + r.status() + ": " + r.body());
    }

    private static void awaitTrue(String what, long timeoutMillis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
            }
            Thread.sleep(100L);
        }
        fail("timed out after " + timeoutMillis + "ms waiting for: " + what);
    }

    @SuppressWarnings("unchecked")
    private static long searchTotal(ClusterMember member, String index) {
        try {
            Response r = request(member, "POST", "/" + index + "/_search", "{\"query\":{\"match_all\":{}},\"size\":0}");
            if (r.status() != 200) {
                return -1L;
            }
            Map<String, Object> hits = (Map<String, Object>) r.json().get("hits");
            return ((Number) ((Map<String, Object>) hits.get("total")).get("value")).longValue();
        } catch (Exception e) {
            return -1L;
        }
    }

    @SuppressWarnings("unchecked")
    private static long totalHits(Response r) {
        Map<String, Object> hits = (Map<String, Object>) r.json().get("hits");
        return ((Number) ((Map<String, Object>) hits.get("total")).get("value")).longValue();
    }

    private static String health(ClusterMember member) {
        try {
            Response r = request(member, "GET", "/_cluster/health", null);
            return String.valueOf(r.json().get("status"));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static void awaitFullyActive(ClusterMember member, int activeShards) throws InterruptedException {
        awaitTrue(activeShards + " active shards on " + member.name, 90_000L, () -> {
            try {
                Map<String, Object> h = request(member, "GET", "/_cluster/health", null).json();
                return "green".equals(h.get("status")) && ((Number) h.get("active_shards")).intValue() == activeShards
                    && ((Number) h.get("initializing_shards")).intValue() == 0;
            } catch (Exception e) {
                return false;
            }
        });
    }

    private static int nodeCount(ClusterMember member) {
        try {
            Response r = request(member, "GET", "/_cluster/health", null);
            return ((Number) r.json().get("number_of_nodes")).intValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private static List<ClusterMember> startCluster(String prefix, Path root, int size, Settings extra) throws Exception {
        List<Integer> ports = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ports.add(freePort());
        }
        List<String> seeds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            seeds.add("127.0.0.1:" + ports.get(i));
            names.add(prefix + "-" + (char) ('a' + i));
        }
        List<ClusterMember> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Settings base = Settings.builder()
                .put("cluster.name", prefix + "-cluster")
                .put("discovery.seed_hosts", String.join(",", seeds))
                .put("cluster.initial_master_nodes", String.join(",", names))
                .put("discovery.election_timeout", "40s")
                .put("cluster.fault_detection.follower_check.interval", "500ms")
                .put("cluster.fault_detection.follower_check.timeout", "5s")
                .put("discovery.find_peers_interval", "300ms")
                .put(extra)
                .build();
            members.add(new ClusterMember(names.get(i), root.resolve(names.get(i)), base));
        }
        List<CompletableFuture<Void>> starts = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ClusterMember member = members.get(i);
            int port = ports.get(i);
            starts.add(CompletableFuture.runAsync(() -> member.start(port)));
        }
        for (CompletableFuture<Void> start : starts) {
            start.join();
        }
        return members;
    }

    private static void awaitFormed(List<ClusterMember> members, int expected) throws InterruptedException {
        awaitTrue("cluster of " + expected + " nodes with a common master", 60_000L, () -> {
            String master = null;
            for (ClusterMember m : members) {
                ClusterState state = m.state();
                if (state.getNodes().size() != expected || state.getNodes().getMasterNodeId() == null) {
                    return false;
                }
                if (master != null && !master.equals(state.getNodes().getMasterNodeId())) {
                    return false;
                }
                master = state.getNodes().getMasterNodeId();
            }
            return true;
        });
    }

    private static ShardRouting primaryOf(ClusterState state, String index, int shard) {
        IndexShardRoutingTable table = state.getRoutingTable().index(index).shard(shard);
        return table.primaryShard();
    }

    private static ClusterMember memberWithId(List<ClusterMember> members, String id) {
        for (ClusterMember m : members) {
            if (m.node != null && m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testThreeNodeClusterReplicatesFailsOverAndRecovers() throws Exception {
        Path root = Files.createTempDirectory("multi-node-test");
        List<ClusterMember> members = startCluster("mn", root, 3, Settings.EMPTY);
        try {
            awaitFormed(members, 3);
            ClusterMember a = members.get(0);
            ClusterMember b = members.get(1);
            ClusterMember c = members.get(2);

            int leaders = 0;
            for (ClusterMember m : members) {
                if (m.node.clusterStateManager().isLeader()) {
                    leaders++;
                }
            }
            assertEquals(1, leaders);

            Response cat = request(c, "GET", "/_cat/nodes?v", null);
            ok(cat);
            for (ClusterMember m : members) {
                assertTrue(cat.body().contains(m.name), cat.body());
            }

            ok(request(b, "PUT", "/orders", "{\"settings\":{\"number_of_shards\":2,\"number_of_replicas\":1},"
                + "\"mappings\":{\"properties\":{\"customer\":{\"type\":\"keyword\"},\"amount\":{\"type\":\"long\"}}}}"));
            awaitFullyActive(a, 4);
            Response green = request(a, "GET", "/_cluster/health?wait_for_status=green&timeout=60s", null);
            ok(green);
            assertEquals("green", green.json().get("status"), green.body());
            assertEquals(3L, ((Number) green.json().get("number_of_nodes")).longValue());
            assertEquals(4L, ((Number) green.json().get("active_shards")).longValue(), green.body());

            ClusterState formed = a.state();
            Set<String> hosting = new HashSet<>();
            for (ShardRouting sr : formed.getRoutingTable().allShards()) {
                assertTrue(sr.started(), sr.toString());
                hosting.add(sr.currentNodeId());
            }
            assertTrue(hosting.size() >= 2, "shards should be spread over nodes: " + hosting);

            for (int i = 0; i < 20; i++) {
                Response indexed = request(a, "PUT", "/orders/_doc/" + i, "{\"customer\":\"c" + (i % 3) + "\",\"amount\":" + i + "}");
                assertEquals(201, indexed.status(), indexed.body());
            }
            awaitTrue("20 docs visible through node C", 30_000L, () -> searchTotal(c, "orders") == 20L);
            for (int i = 0; i < 20; i++) {
                Response got = request(c, "GET", "/orders/_doc/" + i, null);
                assertEquals(200, got.status(), got.body());
                Map<String, Object> source = (Map<String, Object>) got.json().get("_source");
                assertEquals((long) i, ((Number) source.get("amount")).longValue());
            }
            awaitTrue("term query through node B sees all c1 docs", 30_000L, () -> {
                try {
                    Response viaB = request(b, "POST", "/orders/_search", "{\"query\":{\"term\":{\"customer\":\"c1\"}}}");
                    return viaB.status() == 200 && ((Number) ((Map<String, Object>) ((Map<String, Object>) viaB.json().get("hits"))
                        .get("total")).get("value")).longValue() == 7L;
                } catch (Exception e) {
                    return false;
                }
            });

            ShardRouting primary0 = primaryOf(a.state(), "orders", 0);
            String victimId = primary0.currentNodeId();
            ShardRouting replica0 = a.state().getRoutingTable().index("orders").shard(0).replicaShards().get(0);
            String replicaNodeId = replica0.currentNodeId();
            String replicaAllocation = replica0.allocationId().getId();
            long termBefore = a.state().getMetadata().index("orders").primaryTerm(0);
            ClusterMember victim = memberWithId(members, victimId);
            assertNotNull(victim);
            int victimIndex = members.indexOf(victim);
            victim.stop();

            List<ClusterMember> survivors = new ArrayList<>();
            for (ClusterMember m : members) {
                if (m.node != null) {
                    survivors.add(m);
                }
            }
            ClusterMember s1 = survivors.get(0);
            ClusterMember s2 = survivors.get(1);
            awaitTrue("survivors see 2 nodes and yellow health", 60_000L, () -> nodeCount(s1) == 2 && nodeCount(s2) == 2
                && "yellow".equals(health(s1)) && "yellow".equals(health(s2)));
            awaitTrue("replica promoted to primary", 30_000L, () -> {
                ShardRouting p = primaryOf(s1.state(), "orders", 0);
                return p != null && p.active() && replicaAllocation.equals(p.allocationId().getId())
                    && replicaNodeId.equals(p.currentNodeId());
            });
            assertTrue(s1.state().getMetadata().index("orders").primaryTerm(0) > termBefore, "primary term should be bumped");
            assertTrue(memberWithId(members, replicaNodeId).node.indicesService().isLocalPrimary(
                new com.naqqa.elasticsearch.cluster.routing.ShardId("orders", 0)), "promoted node should run the primary group");
            awaitTrue("both primaries active", 30_000L, () -> primaryOf(s1.state(), "orders", 0).active()
                && primaryOf(s1.state(), "orders", 1).active() && primaryOf(s2.state(), "orders", 0).active()
                && primaryOf(s2.state(), "orders", 1).active());

            for (int i = 20; i < 30; i++) {
                ClusterMember writer = i % 2 == 0 ? s1 : s2;
                Response indexed = request(writer, "PUT", "/orders/_doc/" + i, "{\"customer\":\"c" + (i % 3) + "\",\"amount\":" + i + "}");
                assertEquals(201, indexed.status(), indexed.body());
            }
            for (int i = 0; i < 30; i++) {
                ClusterMember reader = i % 2 == 0 ? s2 : s1;
                Response got = request(reader, "GET", "/orders/_doc/" + i, null);
                assertEquals(200, got.status(), "doc " + i + " via " + reader.name + ": " + got.body());
            }
            awaitTrue("30 docs visible after failover", 30_000L, () -> searchTotal(s1, "orders") == 30L && searchTotal(s2, "orders") == 30L);
            assertEquals("yellow", health(s1));

            victim.start(0);
            awaitFormed(members, 3);
            ClusterMember restarted = members.get(victimIndex);
            awaitFullyActive(restarted, 4);
            Response healed = request(restarted, "GET", "/_cluster/health?wait_for_status=green&timeout=60s", null);
            ok(healed);
            assertEquals("green", healed.json().get("status"), healed.body());
            awaitTrue("all members report green", 30_000L, () -> {
                for (ClusterMember m : members) {
                    if (!"green".equals(health(m))) {
                        return false;
                    }
                }
                return true;
            });
            boolean restartedHoldsCopy = false;
            for (ShardRouting sr : restarted.state().getRoutingTable().allShards()) {
                restartedHoldsCopy |= restarted.id().equals(sr.currentNodeId()) && sr.started();
            }
            assertTrue(restartedHoldsCopy, "rejoined node should host recovered shard copies");
            awaitTrue("30 docs visible through the restarted node", 30_000L, () -> searchTotal(restarted, "orders") == 30L);
            for (int i = 0; i < 30; i++) {
                Response got = request(restarted, "GET", "/orders/_doc/" + i, null);
                assertEquals(200, got.status(), got.body());
            }
            Response more = request(restarted, "PUT", "/orders/_doc/after-rejoin?refresh=true", "{\"customer\":\"c9\",\"amount\":99}");
            assertEquals(201, more.status(), more.body());
            Response back = request(a == restarted ? b : a, "GET", "/orders/_doc/after-rejoin", null);
            assertEquals(200, back.status(), back.body());
        } finally {
            for (ClusterMember m : members) {
                try {
                    m.stop();
                } catch (RuntimeException ignored) {
                }
            }
            NodeTestSupport.deleteRecursively(root);
        }
    }

    @Test
    public void testElectedMasterFailureElectsNewMasterAndKeepsServing() throws Exception {
        Path root = Files.createTempDirectory("multi-node-master");
        List<ClusterMember> members = startCluster("mf", root, 3, Settings.EMPTY);
        try {
            awaitFormed(members, 3);
            ok(request(members.get(0), "PUT", "/events", "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":2}}"));
            awaitFullyActive(members.get(1), 3);
            for (int i = 0; i < 5; i++) {
                Response indexed = request(members.get(2), "PUT", "/events/_doc/" + i, "{\"n\":" + i + "}");
                assertEquals(201, indexed.status(), indexed.body());
            }
            String masterId = members.get(0).state().getNodes().getMasterNodeId();
            ClusterMember master = memberWithId(members, masterId);
            assertNotNull(master);
            master.stop();
            List<ClusterMember> survivors = new ArrayList<>();
            for (ClusterMember m : members) {
                if (m.node != null) {
                    survivors.add(m);
                }
            }
            awaitTrue("a new master is elected among the survivors", 60_000L, () -> {
                String first = survivors.get(0).state().getNodes().getMasterNodeId();
                return first != null && !first.equals(masterId) && first.equals(survivors.get(1).state().getNodes().getMasterNodeId())
                    && survivors.get(0).state().getNodes().size() == 2;
            });
            awaitTrue("primary active after master loss", 30_000L, () -> primaryOf(survivors.get(0).state(), "events", 0).active()
                && "yellow".equals(health(survivors.get(1))));
            for (int i = 5; i < 10; i++) {
                Response indexed = request(survivors.get(i % 2), "PUT", "/events/_doc/" + i, "{\"n\":" + i + "}");
                assertEquals(201, indexed.status(), indexed.body());
            }
            for (int i = 0; i < 10; i++) {
                Response got = request(survivors.get((i + 1) % 2), "GET", "/events/_doc/" + i, null);
                assertEquals(200, got.status(), got.body());
            }
            ok(request(survivors.get(1), "PUT", "/after-failover", "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":1}}"));
            awaitTrue("index created after failover is green", 60_000L, () -> {
                try {
                    Response h = request(survivors.get(0), "GET", "/_cluster/health/after-failover", null);
                    return h.status() == 200 && "green".equals(h.json().get("status"));
                } catch (Exception e) {
                    return false;
                }
            });
        } finally {
            for (ClusterMember m : members) {
                try {
                    m.stop();
                } catch (RuntimeException ignored) {
                }
            }
            NodeTestSupport.deleteRecursively(root);
        }
    }

    @Test
    public void testFileBasedSeedHostsDiscoveryJoinsExistingCluster() throws Exception {
        Path root = Files.createTempDirectory("multi-node-seed-file");
        ClusterMember first = null;
        ClusterMember second = null;
        try {
            int firstPort = freePort();
            Settings firstSettings = Settings.builder()
                .put("cluster.name", "seedfile-cluster")
                .put("discovery.seed_hosts", "127.0.0.1:" + firstPort)
                .put("cluster.initial_master_nodes", "seed-a")
                .put("discovery.find_peers_interval", "300ms")
                .build();
            first = new ClusterMember("seed-a", root.resolve("seed-a"), firstSettings);
            first.start(firstPort);

            Path secondHome = root.resolve("seed-b");
            Files.createDirectories(secondHome.resolve("config"));
            Files.writeString(secondHome.resolve("config").resolve("unicast_hosts.txt"),
                "# seed hosts\n127.0.0.1:" + firstPort + "\n", StandardCharsets.UTF_8);
            Settings secondSettings = Settings.builder()
                .put("cluster.name", "seedfile-cluster")
                .put("discovery.seed_providers", "file")
                .put("discovery.find_peers_interval", "300ms")
                .put("discovery.election_timeout", "20s")
                .build();
            second = new ClusterMember("seed-b", secondHome, secondSettings);
            second.start(0);
            awaitFormed(List.of(first, second), 2);
            assertEquals(first.id(), second.state().getNodes().getMasterNodeId());
            ok(request(second, "PUT", "/seeded", "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":1}}"));
            awaitFullyActive(first, 2);
            Response green = request(first, "GET", "/_cluster/health?wait_for_status=green&timeout=60s", null);
            assertEquals("green", green.json().get("status"), green.body());
            Response indexed = request(second, "PUT", "/seeded/_doc/1", "{\"v\":1}");
            assertEquals(201, indexed.status(), indexed.body());
            Response got = request(first, "GET", "/seeded/_doc/1", null);
            assertEquals(200, got.status(), got.body());
        } finally {
            if (second != null) {
                second.stop();
            }
            if (first != null) {
                first.stop();
            }
            NodeTestSupport.deleteRecursively(root);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testFullClusterRestartPersistsClusterWideMetadataAndSecurity() throws Exception {
        Path root = Files.createTempDirectory("multi-node-full-restart");
        String su = "elastic";
        String suPass = "Bootstrap-pw1";
        Settings secured = Settings.builder().put("xpack.security.enabled", "true").put("bootstrap.password", suPass).build();
        List<ClusterMember> members = startCluster("fr", root, 3, secured);
        try {
            awaitFormed(members, 3);
            ClusterMember a = members.get(0);
            ClusterMember b = members.get(1);
            ClusterMember c = members.get(2);
            List<Integer> ports = new ArrayList<>();
            for (ClusterMember m : members) {
                ports.add(m.lastPort);
            }

            ok(request(a, "PUT", "/_index_template/restart-tpl",
                "{\"index_patterns\":[\"restart-*\"],\"template\":{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":1},"
                    + "\"mappings\":{\"properties\":{\"tag\":{\"type\":\"keyword\"}}}}}", su, suPass));
            ok(request(a, "PUT", "/_ingest/pipeline/restart-pipeline",
                "{\"processors\":[{\"set\":{\"field\":\"stamped\",\"value\":\"yes\"}}]}", su, suPass));
            ok(request(a, "PUT", "/_scripts/restart-script",
                "{\"script\":{\"lang\":\"painless\",\"source\":\"ctx._source.scripted = true\"}}", su, suPass));
            ok(request(a, "PUT", "/_ilm/policy/restart-policy",
                "{\"policy\":{\"phases\":{\"hot\":{\"actions\":{\"set_priority\":{\"priority\":50}}}}}}", su, suPass));
            ok(request(a, "PUT", "/restart-idx-000001",
                "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":1,\"index.lifecycle.name\":\"restart-policy\"},"
                    + "\"aliases\":{\"restart-alias\":{\"filter\":{\"term\":{\"tag\":\"keep\"}}}}}", su, suPass));
            ok(request(a, "PUT", "/_security/user/produser", "{\"password\":\"Produser-pw1\",\"roles\":[\"superuser\"]}", su, suPass));

            awaitTrue("restart-idx-000001 is green", 60_000L, () -> {
                try {
                    Response h = request(a, "GET", "/_cluster/health/restart-idx-000001?wait_for_status=green&timeout=5s", null, su,
                        suPass);
                    return h.status() == 200 && "green".equals(h.json().get("status"));
                } catch (Exception e) {
                    return false;
                }
            });
            ok(request(b, "PUT", "/restart-idx-000001/_doc/1?pipeline=restart-pipeline&refresh=true", "{\"tag\":\"keep\"}", su, suPass));
            ok(request(b, "PUT", "/restart-idx-000001/_doc/2?pipeline=restart-pipeline&refresh=true", "{\"tag\":\"drop\"}", su, suPass));

            Response doc1 = request(c, "GET", "/restart-idx-000001/_doc/1", null, su, suPass);
            assertEquals(200, doc1.status(), doc1.body());
            assertEquals("yes", ((Map<String, Object>) doc1.json().get("_source")).get("stamped"), doc1.body());
            Response beforeRestartAlias = request(c, "POST", "/restart-alias/_search", "{\"query\":{\"match_all\":{}}}", su, suPass);
            assertEquals(1L, totalHits(beforeRestartAlias), beforeRestartAlias.body());

            for (ClusterMember m : members) {
                m.stop();
            }
            List<CompletableFuture<Void>> restarts = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                ClusterMember m = members.get(i);
                int port = ports.get(i);
                restarts.add(CompletableFuture.runAsync(() -> m.start(port)));
            }
            for (CompletableFuture<Void> r : restarts) {
                r.join();
            }
            awaitFormed(members, 3);

            awaitTrue("restart-idx-000001 is green again after full restart", 60_000L, () -> {
                try {
                    Response h = request(a, "GET", "/_cluster/health/restart-idx-000001?wait_for_status=green&timeout=5s", null, su,
                        suPass);
                    return h.status() == 200 && "green".equals(h.json().get("status"));
                } catch (Exception e) {
                    return false;
                }
            });

            Response tpl = request(b, "GET", "/_index_template/restart-tpl", null, su, suPass);
            assertEquals(200, tpl.status(), tpl.body());
            Response pipeline = request(b, "GET", "/_ingest/pipeline/restart-pipeline", null, su, suPass);
            assertEquals(200, pipeline.status(), pipeline.body());
            Response script = request(b, "GET", "/_scripts/restart-script", null, su, suPass);
            assertEquals(200, script.status(), script.body());
            Response policy = request(b, "GET", "/_ilm/policy/restart-policy", null, su, suPass);
            assertEquals(200, policy.status(), policy.body());

            Response docAfter = request(c, "GET", "/restart-idx-000001/_doc/1", null, su, suPass);
            assertEquals(200, docAfter.status(), docAfter.body());
            Response aliasAfter = request(c, "POST", "/restart-alias/_search", "{\"query\":{\"match_all\":{}}}", su, suPass);
            assertEquals(1L, totalHits(aliasAfter), aliasAfter.body());

            ok(request(c, "PUT", "/restart-idx-000001/_doc/3?pipeline=restart-pipeline&refresh=true", "{\"tag\":\"keep\"}", su, suPass));
            Response doc3 = request(a, "GET", "/restart-idx-000001/_doc/3", null, su, suPass);
            assertEquals("yes", ((Map<String, Object>) doc3.json().get("_source")).get("stamped"), doc3.body());
            Response aliasAfterInsert = request(a, "POST", "/restart-alias/_search", "{\"query\":{\"match_all\":{}}}", su, suPass);
            assertEquals(2L, totalHits(aliasAfterInsert), aliasAfterInsert.body());

            Response auth = request(a, "GET", "/_security/_authenticate", null, "produser", "Produser-pw1");
            assertEquals(200, auth.status(), auth.body());
            assertEquals("produser", auth.json().get("username"), auth.body());
        } finally {
            for (ClusterMember m : members) {
                try {
                    m.stop();
                } catch (RuntimeException ignored) {
                }
            }
            NodeTestSupport.deleteRecursively(root);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testTemplateAndPipelineCreatedOnNodeAAreUsableFromNodeC() throws Exception {
        Path root = Files.createTempDirectory("multi-node-template-cross");
        List<ClusterMember> members = startCluster("tc", root, 3, Settings.EMPTY);
        try {
            awaitFormed(members, 3);
            ClusterMember a = members.get(0);
            ClusterMember c = members.get(2);

            ok(request(a, "PUT", "/_index_template/cross-tpl",
                "{\"index_patterns\":[\"cross-*\"],\"template\":{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":1},"
                    + "\"mappings\":{\"properties\":{\"stamped\":{\"type\":\"keyword\"}}}}}", null, null));
            ok(request(a, "PUT", "/_ingest/pipeline/cross-pipeline",
                "{\"processors\":[{\"set\":{\"field\":\"stamped\",\"value\":\"cross-ok\"}}]}", null, null));

            ok(request(c, "PUT", "/cross-000001", "{}"));
            awaitFullyActive(c, 2);

            ok(request(c, "PUT", "/cross-000001/_doc/1?pipeline=cross-pipeline&refresh=true", "{\"x\":1}"));
            Response got = request(a, "GET", "/cross-000001/_doc/1", null);
            assertEquals(200, got.status(), got.body());
            assertEquals("cross-ok", ((Map<String, Object>) got.json().get("_source")).get("stamped"), got.body());

            Response settings = request(c, "GET", "/cross-000001/_settings", null);
            Map<String, Object> idx = (Map<String, Object>) settings.json().get("cross-000001");
            Map<String, Object> s = (Map<String, Object>) idx.get("settings");
            Map<String, Object> index = (Map<String, Object>) s.get("index");
            assertEquals("1", String.valueOf(index.get("number_of_replicas")), settings.body());
        } finally {
            for (ClusterMember m : members) {
                try {
                    m.stop();
                } catch (RuntimeException ignored) {
                }
            }
            NodeTestSupport.deleteRecursively(root);
        }
    }

    @Test
    public void testRefreshFromOneNodeMakesDocsVisibleOnReplicasEverywhere() throws Exception {
        Path root = Files.createTempDirectory("multi-node-refresh");
        List<ClusterMember> members = startCluster("rf", root, 3, Settings.EMPTY);
        try {
            awaitFormed(members, 3);
            ClusterMember a = members.get(0);
            ClusterMember b = members.get(1);
            ok(request(a, "PUT", "/refresh-idx", "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":2}}"));
            awaitFullyActive(a, 3);

            ok(request(a, "PUT", "/refresh-idx/_doc/1", "{\"v\":1}"));
            ok(request(b, "POST", "/refresh-idx/_refresh", null));

            for (ClusterMember m : members) {
                awaitTrue("doc visible via local shard copy on " + m.name, 15_000L, () -> {
                    try {
                        Response r = request(m, "POST", "/refresh-idx/_search?preference=_local", "{\"query\":{\"match_all\":{}}}");
                        return r.status() == 200 && totalHits(r) == 1L;
                    } catch (Exception e) {
                        return false;
                    }
                });
            }
        } finally {
            for (ClusterMember m : members) {
                try {
                    m.stop();
                } catch (RuntimeException ignored) {
                }
            }
            NodeTestSupport.deleteRecursively(root);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDeleteByQueryAcrossThreeNodesRemovesDocsFromAllShards() throws Exception {
        Path root = Files.createTempDirectory("multi-node-dbq");
        List<ClusterMember> members = startCluster("dq", root, 3, Settings.EMPTY);
        try {
            awaitFormed(members, 3);
            ClusterMember a = members.get(0);
            ClusterMember b = members.get(1);
            ClusterMember c = members.get(2);
            ok(request(a, "PUT", "/dbq-idx", "{\"settings\":{\"number_of_shards\":3,\"number_of_replicas\":1}}"));
            awaitFullyActive(a, 6);

            for (int i = 0; i < 30; i++) {
                Response indexed = request(b, "PUT", "/dbq-idx/_doc/" + i + "?refresh=true", "{\"v\":" + i + "}");
                assertEquals(201, indexed.status(), indexed.body());
            }
            awaitTrue("30 docs visible before delete", 20_000L, () -> searchTotal(c, "dbq-idx") == 30L);

            Set<String> primaryNodes = new HashSet<>();
            ClusterState state = a.state();
            for (ShardRouting sr : state.getRoutingTable().index("dbq-idx").getShards().values().iterator().next().getShards()) {
                primaryNodes.add(sr.currentNodeId());
            }

            Response resp = request(a, "POST", "/dbq-idx/_delete_by_query?error_trace=true", "{\"query\":{\"match_all\":{}}}");
            ok(resp);
            assertEquals(30L, ((Number) resp.json().get("deleted")).longValue(), resp.body());

            awaitTrue("0 docs remain on every node", 20_000L, () -> searchTotal(a, "dbq-idx") == 0L
                && searchTotal(b, "dbq-idx") == 0L && searchTotal(c, "dbq-idx") == 0L);
        } finally {
            for (ClusterMember m : members) {
                try {
                    m.stop();
                } catch (RuntimeException ignored) {
                }
            }
            NodeTestSupport.deleteRecursively(root);
        }
    }

    @Test
    public void testClusterHealthYellowDuringReplicaRecoveryThenGreen() throws Exception {
        Path root = Files.createTempDirectory("multi-node-health");
        List<ClusterMember> members = startCluster("hy", root, 3, Settings.EMPTY);
        try {
            awaitFormed(members, 3);
            ClusterMember a = members.get(0);
            ClusterMember b = members.get(1);
            ClusterMember c = members.get(2);
            ok(request(a, "PUT", "/health-idx", "{\"settings\":{\"number_of_shards\":2,\"number_of_replicas\":1}}"));
            awaitFullyActive(a, 4);
            assertEquals("green", health(a));

            for (int i = 0; i < 3000; i++) {
                Response indexed = request(b, "PUT", "/health-idx/_doc/" + i, "{\"v\":" + i + "}");
                assertEquals(201, indexed.status(), indexed.body());
            }
            ok(request(b, "POST", "/health-idx/_refresh", null));

            c.stop();
            awaitTrue("cluster is yellow with a node down", 30_000L, () -> "yellow".equals(health(a)));

            int port = c.lastPort;
            c.start(port);

            boolean sawInitializingWhileYellow = false;
            long deadline = System.currentTimeMillis() + 60_000L;
            while (System.currentTimeMillis() < deadline) {
                ClusterState state = a.state();
                IndexRoutingTable irt = state.getRoutingTable().index("health-idx");
                boolean anyInitializing = false;
                if (irt != null) {
                    for (IndexShardRoutingTable table : irt.getShards().values()) {
                        for (ShardRouting sr : table.getShards()) {
                            if (sr.initializing()) {
                                anyInitializing = true;
                            }
                        }
                    }
                }
                if (anyInitializing) {
                    sawInitializingWhileYellow = "yellow".equals(health(a));
                    break;
                }
                if ("green".equals(health(a))) {
                    break;
                }
                Thread.sleep(2L);
            }
            assertTrue(sawInitializingWhileYellow, "expected to observe yellow health while a replica was still recovering");
            awaitTrue("cluster returns to green once recovery completes", 60_000L, () -> "green".equals(health(a)));
        } finally {
            for (ClusterMember m : members) {
                try {
                    m.stop();
                } catch (RuntimeException ignored) {
                }
            }
            NodeTestSupport.deleteRecursively(root);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSnapshotRestoreAcrossNodesAllocatesToOtherNodesAndIsSearchableEverywhere() throws Exception {
        Path root = Files.createTempDirectory("multi-node-snapshot-restore");
        List<ClusterMember> members = startCluster("sr", root, 3, Settings.EMPTY);
        try {
            awaitFormed(members, 3);
            ClusterMember a = members.get(0);
            ClusterMember b = members.get(1);
            ClusterMember c = members.get(2);

            ok(request(a, "PUT", "/src-idx", "{\"settings\":{\"number_of_shards\":2,\"number_of_replicas\":1}}"));
            awaitFullyActive(a, 4);
            for (int i = 0; i < 20; i++) {
                Response indexed = request(b, "PUT", "/src-idx/_doc/" + i + "?refresh=true", "{\"v\":" + i + "}");
                assertEquals(201, indexed.status(), indexed.body());
            }
            awaitTrue("20 docs visible before snapshot", 20_000L, () -> searchTotal(c, "src-idx") == 20L);

            ok(request(a, "PUT", "/_snapshot/backup", "{\"type\":\"fs\",\"settings\":{\"location\":\"backup\"}}"));
            Response snap = request(a, "PUT", "/_snapshot/backup/snap1?wait_for_completion=true", "{\"indices\":\"src-idx\"}");
            ok(snap);
            assertEquals("SUCCESS", ((Map<String, Object>) snap.json().get("snapshot")).get("state"), snap.body());

            String initiatorId = a.id();
            Response restore = request(a, "POST", "/_snapshot/backup/snap1/_restore?wait_for_completion=true",
                "{\"indices\":\"src-idx\",\"rename_pattern\":\"src-idx\",\"rename_replacement\":\"restored-idx\"}");
            ok(restore);

            awaitTrue("restored-idx is green on every node", 60_000L, () -> {
                for (ClusterMember m : members) {
                    try {
                        Response h = request(m, "GET", "/_cluster/health/restored-idx?wait_for_status=green&timeout=5s", null);
                        if (h.status() != 200 || !"green".equals(h.json().get("status"))) {
                            return false;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
                return true;
            });

            ClusterState state = a.state();
            Set<String> restoredHostNodes = new HashSet<>();
            IndexRoutingTable irt = state.getRoutingTable().index("restored-idx");
            assertNotNull(irt);
            for (IndexShardRoutingTable table : irt.getShards().values()) {
                for (ShardRouting sr : table.getShards()) {
                    assertTrue(sr.started(), sr.toString());
                    restoredHostNodes.add(sr.currentNodeId());
                }
            }
            assertTrue(restoredHostNodes.stream().anyMatch(id -> !id.equals(initiatorId)),
                "restored shards should be allocated across the cluster, not only the initiating node: " + restoredHostNodes);

            for (ClusterMember m : members) {
                awaitTrue("20 restored docs visible via " + m.name, 20_000L, () -> searchTotal(m, "restored-idx") == 20L);
            }
            for (int i = 0; i < 20; i++) {
                ClusterMember reader = members.get(i % members.size());
                Response got = request(reader, "GET", "/restored-idx/_doc/" + i, null);
                assertEquals(200, got.status(), "doc " + i + " via " + reader.name + ": " + got.body());
                Map<String, Object> source = (Map<String, Object>) got.json().get("_source");
                assertEquals((long) i, ((Number) source.get("v")).longValue());
            }

            ok(request(b, "PUT", "/restored-idx/_doc/after-restore?refresh=true", "{\"v\":99}"));
            Response after = request(c, "GET", "/restored-idx/_doc/after-restore", null);
            assertEquals(200, after.status(), after.body());
        } finally {
            for (ClusterMember m : members) {
                try {
                    m.stop();
                } catch (RuntimeException ignored) {
                }
            }
            NodeTestSupport.deleteRecursively(root);
        }
    }
}
