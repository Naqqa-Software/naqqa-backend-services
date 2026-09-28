package com.naqqa.elasticsearch.node.rest;

import com.naqqa.elasticsearch.http.RestChannel;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;
import com.naqqa.elasticsearch.http.Router;
import com.naqqa.elasticsearch.ingest.IngestService;
import com.naqqa.elasticsearch.ingest.Pipeline;
import com.naqqa.elasticsearch.ingest.PipelineFactory;
import com.naqqa.elasticsearch.monitor.health.HealthReport;
import com.naqqa.elasticsearch.node.indices.LifecycleService;
import com.naqqa.elasticsearch.node.security.SecurityService;
import com.naqqa.elasticsearch.node.snapshots.SnapshotsService;
import com.naqqa.elasticsearch.node.support.IndexResolver;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.rest.support.RestUtils;
import com.naqqa.elasticsearch.script.ScriptService;
import com.naqqa.elasticsearch.script.StoredScriptSource;
import com.naqqa.elasticsearch.security.authc.ApiKeyService;
import com.naqqa.elasticsearch.security.authc.Authentication;
import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

public final class NodeRestHandlers {

    private final IngestService ingestService;
    private final SnapshotsService snapshotsService;
    private final LifecycleService lifecycleService;
    private final SecurityService securityService;
    private final ScriptService scriptService;
    private final Supplier<HealthReport> healthReport;
    private final Supplier<com.naqqa.elasticsearch.cluster.state.ClusterState> state;
    private final IndexResolver indexResolver;

    public NodeRestHandlers(IngestService ingestService, SnapshotsService snapshotsService, LifecycleService lifecycleService,
                            SecurityService securityService, ScriptService scriptService, Supplier<HealthReport> healthReport,
                            Supplier<com.naqqa.elasticsearch.cluster.state.ClusterState> state, IndexResolver indexResolver) {
        this.ingestService = ingestService;
        this.snapshotsService = snapshotsService;
        this.lifecycleService = lifecycleService;
        this.securityService = securityService;
        this.scriptService = scriptService;
        this.healthReport = healthReport;
        this.state = state;
        this.indexResolver = indexResolver;
    }

    public void registerAll(Router router) {
        router.register(RestMethod.PUT, "/_ingest/pipeline/{id}", this::putPipeline);
        router.register(RestMethod.GET, "/_ingest/pipeline", this::getPipeline);
        router.register(RestMethod.GET, "/_ingest/pipeline/{id}", this::getPipeline);
        router.register(RestMethod.DELETE, "/_ingest/pipeline/{id}", this::deletePipeline);
        router.register(RestMethod.POST, "/_ingest/pipeline/_simulate", this::simulatePipeline);
        router.register(RestMethod.GET, "/_ingest/pipeline/_simulate", this::simulatePipeline);
        router.register(RestMethod.POST, "/_ingest/pipeline/{id}/_simulate", this::simulatePipeline);
        router.register(RestMethod.GET, "/_ingest/pipeline/{id}/_simulate", this::simulatePipeline);

        router.register(RestMethod.PUT, "/_snapshot/{repository}", this::putRepository);
        router.register(RestMethod.POST, "/_snapshot/{repository}", this::putRepository);
        router.register(RestMethod.GET, "/_snapshot", this::getRepositories);
        router.register(RestMethod.GET, "/_snapshot/{repository}", this::getRepositories);
        router.register(RestMethod.DELETE, "/_snapshot/{repository}", this::deleteRepository);
        router.register(RestMethod.POST, "/_snapshot/{repository}/_verify", this::verifyRepository);
        router.register(RestMethod.PUT, "/_snapshot/{repository}/{snapshot}", this::createSnapshot);
        router.register(RestMethod.POST, "/_snapshot/{repository}/{snapshot}", this::createSnapshot);
        router.register(RestMethod.GET, "/_snapshot/{repository}/{snapshot}", this::getSnapshots);
        router.register(RestMethod.DELETE, "/_snapshot/{repository}/{snapshot}", this::deleteSnapshot);
        router.register(RestMethod.POST, "/_snapshot/{repository}/{snapshot}/_restore", this::restoreSnapshot);

        router.register(RestMethod.PUT, "/_ilm/policy/{name}", this::putPolicy);
        router.register(RestMethod.GET, "/_ilm/policy", this::getPolicy);
        router.register(RestMethod.GET, "/_ilm/policy/{name}", this::getPolicy);
        router.register(RestMethod.DELETE, "/_ilm/policy/{name}", this::deletePolicy);
        router.register(RestMethod.GET, "/{index}/_ilm/explain", this::explainLifecycle);
        router.register(RestMethod.POST, "/{index}/_ilm/retry", this::retryLifecycle);
        router.register(RestMethod.POST, "/_ilm/start", (r, c) -> ilmRunning(r, c, true));
        router.register(RestMethod.POST, "/_ilm/stop", (r, c) -> ilmRunning(r, c, false));
        router.register(RestMethod.GET, "/_ilm/status", this::ilmStatus);

        router.register(RestMethod.GET, "/_security/_authenticate", this::authenticate);
        router.register(RestMethod.PUT, "/_security/user/{username}", this::putUser);
        router.register(RestMethod.POST, "/_security/user/{username}", this::putUser);
        router.register(RestMethod.GET, "/_security/user", this::getUsers);
        router.register(RestMethod.GET, "/_security/user/{username}", this::getUsers);
        router.register(RestMethod.DELETE, "/_security/user/{username}", this::deleteUser);
        router.register(RestMethod.PUT, "/_security/role/{name}", this::putRole);
        router.register(RestMethod.POST, "/_security/role/{name}", this::putRole);
        router.register(RestMethod.GET, "/_security/role", this::getRoles);
        router.register(RestMethod.GET, "/_security/role/{name}", this::getRoles);
        router.register(RestMethod.DELETE, "/_security/role/{name}", this::deleteRole);
        router.register(RestMethod.POST, "/_security/api_key", this::createApiKey);
        router.register(RestMethod.PUT, "/_security/api_key", this::createApiKey);
        router.register(RestMethod.DELETE, "/_security/api_key", this::invalidateApiKey);

        router.register(RestMethod.PUT, "/_scripts/{id}", this::putScript);
        router.register(RestMethod.POST, "/_scripts/{id}", this::putScript);
        router.register(RestMethod.GET, "/_scripts/{id}", this::getScript);
        router.register(RestMethod.DELETE, "/_scripts/{id}", this::deleteScript);

        router.register(RestMethod.GET, "/_health_report", this::healthReport);
    }

    private static void ok(RestRequest request, RestChannel channel, Map<String, Object> body) {
        RestUtils.sendJson(channel, request, 200, body);
    }

    private static Map<String, Object> ack() {
        return Map.of("acknowledged", true);
    }

    private void putPipeline(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        String id = request.param("id");
        Map<String, Object> body = RestUtils.parseBody(request);
        try {
            ingestService.putPipeline(id, body);
        } catch (RestApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RestApiException(400, e.getMessage(), e);
        }
        ok(request, channel, ack());
    }

    private void getPipeline(RestRequest request, RestChannel channel) {
        String id = request.param("id");
        Map<String, Object> out = new TreeMap<>();
        String pattern = id == null ? "*" : id;
        for (String p : pattern.split(",")) {
            for (Map.Entry<String, Pipeline> e : ingestService.getPipelineStore().getMatching(p).entrySet()) {
                out.put(e.getKey(), ingestService.getPipelineStore().getConfig(e.getKey()));
            }
        }
        if (out.isEmpty() && id != null) {
            RestUtils.sendJson(channel, request, 404, Map.of());
            return;
        }
        ok(request, channel, new LinkedHashMap<>(out));
    }

    private void deletePipeline(RestRequest request, RestChannel channel) {
        String id = request.param("id");
        ingestService.deletePipeline(id);
        ok(request, channel, ack());
    }

    @SuppressWarnings("unchecked")
    private void simulatePipeline(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        String id = request.param("id");
        Pipeline pipeline;
        if (id != null) {
            pipeline = ingestService.getPipelineStore().get(id);
            if (pipeline == null) {
                throw new RestApiException(404, "pipeline [" + id + "] does not exist");
            }
        } else {
            Map<String, Object> config = SettingsMaps.asMap(body.get("pipeline"));
            if (config == null) {
                throw new RestApiException(400, "required property is missing [pipeline]");
            }
            try {
                pipeline = PipelineFactory.create("_simulate_pipeline", config, ingestService.getRegistry());
            } catch (RuntimeException e) {
                throw new RestApiException(400, e.getMessage(), e);
            }
        }
        List<Map<String, Object>> docs = new ArrayList<>();
        if (body.get("docs") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> doc = SettingsMaps.asMap(o);
                if (doc != null) {
                    docs.add(doc);
                }
            }
        }
        ok(request, channel, ingestService.simulate(pipeline, docs, request.paramAsBoolean("verbose", false)));
    }

    private void putRepository(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        snapshotsService.register(request.param("repository"), String.valueOf(body.get("type")),
            SettingsMaps.asMap(body.get("settings")), request.paramAsBoolean("verify", true), true);
        ok(request, channel, ack());
    }

    private void getRepositories(RestRequest request, RestChannel channel) {
        String name = request.param("repository");
        Map<String, Object> out = new TreeMap<>();
        for (SnapshotsService.RepositoryEntry entry : snapshotsService.repositories().values()) {
            if (name == null || "_all".equals(name) || name.equals(entry.name())
                || com.naqqa.elasticsearch.common.regex.Regex.simpleMatch(name, entry.name())) {
                out.put(entry.name(), Map.of("type", entry.type(), "settings", entry.settings()));
            }
        }
        if (out.isEmpty() && name != null && !"_all".equals(name) && !name.contains("*")) {
            throw new RestApiException(404, "[" + name + "] missing");
        }
        ok(request, channel, new LinkedHashMap<>(out));
    }

    private void deleteRepository(RestRequest request, RestChannel channel) {
        snapshotsService.deleteRepository(request.param("repository"));
        ok(request, channel, ack());
    }

    private void verifyRepository(RestRequest request, RestChannel channel) {
        snapshotsService.verify(request.param("repository"));
        ok(request, channel, Map.of("nodes", Map.of()));
    }

    private void createSnapshot(RestRequest request, RestChannel channel) {
        Map<String, Object> body = RestUtils.parseBody(request);
        List<String> indices = SettingsMaps.asStringList(body.get("indices"));
        SnapshotInfo info = snapshotsService.createSnapshot(request.param("repository"), request.param("snapshot"), indices);
        if (request.paramAsBoolean("wait_for_completion", false)) {
            ok(request, channel, Map.of("snapshot", snapshotsService.render(info)));
        } else {
            ok(request, channel, Map.of("accepted", true));
        }
    }

    private void getSnapshots(RestRequest request, RestChannel channel) {
        String repository = request.param("repository");
        String name = request.param("snapshot");
        List<Object> snapshots = new ArrayList<>();
        for (SnapshotInfo info : snapshotsService.snapshots(repository)) {
            if ("_all".equals(name) || "*".equals(name) || info.name().equals(name)
                || com.naqqa.elasticsearch.common.regex.Regex.simpleMatch(name, info.name())) {
                snapshots.add(snapshotsService.render(info));
            }
        }
        if (snapshots.isEmpty() && !"_all".equals(name) && !name.contains("*")) {
            throw new RestApiException(404, "[" + repository + ":" + name + "] is missing");
        }
        ok(request, channel, Map.of("snapshots", snapshots, "total", snapshots.size(), "remaining", 0));
    }

    private void deleteSnapshot(RestRequest request, RestChannel channel) {
        snapshotsService.deleteSnapshot(request.param("repository"), request.param("snapshot"));
        ok(request, channel, ack());
    }

    private void restoreSnapshot(RestRequest request, RestChannel channel) {
        Map<String, Object> body = RestUtils.parseBody(request);
        Map<String, Object> result = snapshotsService.restore(request.param("repository"), request.param("snapshot"), body);
        if (request.paramAsBoolean("wait_for_completion", false)) {
            ok(request, channel, result);
        } else {
            ok(request, channel, Map.of("accepted", true));
        }
    }

    private void putPolicy(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        lifecycleService.putPolicy(request.param("name"), RestUtils.parseBody(request));
        ok(request, channel, ack());
    }

    private void getPolicy(RestRequest request, RestChannel channel) {
        ok(request, channel, lifecycleService.getPolicies(request.param("name")));
    }

    private void deletePolicy(RestRequest request, RestChannel channel) {
        lifecycleService.deletePolicy(request.param("name"));
        ok(request, channel, ack());
    }

    private void explainLifecycle(RestRequest request, RestChannel channel) {
        List<String> indices = indexResolver.resolve(state.get(), request.paramAsList("index"), true, false);
        ok(request, channel, lifecycleService.explain(indices));
    }

    private void retryLifecycle(RestRequest request, RestChannel channel) {
        for (String index : indexResolver.resolve(state.get(), request.paramAsList("index"), true, false)) {
            try {
                lifecycleService.retry(index);
            } catch (RuntimeException e) {
                throw new RestApiException(400, e.getMessage(), e);
            }
        }
        ok(request, channel, ack());
    }

    private void ilmRunning(RestRequest request, RestChannel channel, boolean running) {
        lifecycleService.setRunning(running);
        ok(request, channel, ack());
    }

    private void ilmStatus(RestRequest request, RestChannel channel) {
        ok(request, channel, Map.of("operation_mode", lifecycleService.running() ? "RUNNING" : "STOPPED"));
    }

    private Authentication requireAuthentication() {
        Authentication authentication = NodeRestFilter.currentAuthentication();
        if (authentication == null) {
            throw new RestApiException(400, "security is disabled on this node; set [xpack.security.enabled: true] to use this API");
        }
        return authentication;
    }

    private void authenticate(RestRequest request, RestChannel channel) {
        ok(request, channel, securityService.describe(requireAuthentication()));
    }

    private void putUser(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        String username = request.param("username");
        boolean existed = !securityService.getUsers().containsKey(username) ? false : true;
        String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
        securityService.putUser(username, password == null ? null : password.toCharArray(),
            SettingsMaps.asStringList(body.get("roles")),
            body.get("full_name") == null ? null : String.valueOf(body.get("full_name")),
            body.get("email") == null ? null : String.valueOf(body.get("email")));
        ok(request, channel, Map.of("created", !existed));
    }

    @SuppressWarnings("unchecked")
    private void getUsers(RestRequest request, RestChannel channel) {
        String name = request.param("username");
        Map<String, Object> users = securityService.getUsers();
        if (name != null) {
            Object user = users.get(name);
            if (user == null) {
                RestUtils.sendJson(channel, request, 404, Map.of());
                return;
            }
            ok(request, channel, Map.of(name, user));
            return;
        }
        ok(request, channel, users);
    }

    private void deleteUser(RestRequest request, RestChannel channel) {
        boolean found = securityService.deleteUser(request.param("username"));
        RestUtils.sendJson(channel, request, found ? 200 : 404, Map.of("found", found));
    }

    private void putRole(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        String name = request.param("name");
        boolean existed = securityService.getRoles().containsKey(name);
        securityService.putRole(name, RestUtils.parseBody(request));
        ok(request, channel, Map.of("role", Map.of("created", !existed)));
    }

    private void getRoles(RestRequest request, RestChannel channel) {
        String name = request.param("name");
        Map<String, Object> roles = securityService.getRoles();
        if (name != null) {
            Object role = roles.get(name);
            if (role == null) {
                RestUtils.sendJson(channel, request, 404, Map.of());
                return;
            }
            ok(request, channel, Map.of(name, role));
            return;
        }
        ok(request, channel, roles);
    }

    private void deleteRole(RestRequest request, RestChannel channel) {
        boolean found = securityService.deleteRole(request.param("name"));
        RestUtils.sendJson(channel, request, found ? 200 : 404, Map.of("found", found));
    }

    private void createApiKey(RestRequest request, RestChannel channel) {
        Authentication authentication = requireAuthentication();
        Map<String, Object> body = RestUtils.parseBody(request);
        String name = body.get("name") == null ? "api-key" : String.valueOf(body.get("name"));
        Duration ttl = null;
        if (body.get("expiration") != null) {
            ttl = Duration.ofMillis(com.naqqa.elasticsearch.common.unit.TimeValue.parseTimeValue(
                String.valueOf(body.get("expiration")), "expiration").millis());
        }
        ApiKeyService.CreatedApiKey created = securityService.createApiKey(authentication.effectiveUser(), name, ttl);
        String encoded = created.credentials().substring("ApiKey ".length());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", created.id());
        out.put("name", name);
        if (created.expirationTime() != null) {
            out.put("expiration", created.expirationTime().toEpochMilli());
        }
        out.put("encoded", encoded);
        ok(request, channel, out);
    }

    private void invalidateApiKey(RestRequest request, RestChannel channel) {
        Map<String, Object> body = RestUtils.parseBody(request);
        List<String> ids = new ArrayList<>(SettingsMaps.asStringList(body.get("ids")));
        if (body.get("id") != null) {
            ids.add(String.valueOf(body.get("id")));
        }
        List<String> invalidated = new ArrayList<>();
        for (String id : ids) {
            if (securityService.invalidateApiKey(id)) {
                invalidated.add(id);
            }
        }
        ok(request, channel, Map.of("invalidated_api_keys", invalidated, "previously_invalidated_api_keys", List.of(),
            "error_count", 0));
    }

    private void putScript(RestRequest request, RestChannel channel) {
        RestUtils.requireContent(request);
        Map<String, Object> body = RestUtils.parseBody(request);
        Map<String, Object> script = SettingsMaps.asMap(body.get("script"));
        if (script == null) {
            throw new RestApiException(400, "must specify [script] for stored script");
        }
        try {
            scriptService.putStoredScript(request.param("id"), StoredScriptSource.parse(body));
        } catch (RestApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RestApiException(400, e.getMessage(), e);
        }
        ok(request, channel, ack());
    }

    private void getScript(RestRequest request, RestChannel channel) {
        String id = request.param("id");
        StoredScriptSource source = scriptService.hasStoredScript(id) ? scriptService.getStoredScript(id) : null;
        if (source == null) {
            RestUtils.sendJson(channel, request, 404, Map.of("_id", id, "found", false));
            return;
        }
        ok(request, channel, Map.of("_id", id, "found", true, "script", source.toMap()));
    }

    private void deleteScript(RestRequest request, RestChannel channel) {
        String id = request.param("id");
        if (!scriptService.hasStoredScript(id)) {
            throw new RestApiException(404, "stored script [" + id + "] does not exist");
        }
        scriptService.deleteStoredScript(id);
        ok(request, channel, ack());
    }

    private void healthReport(RestRequest request, RestChannel channel) {
        HealthReport report = healthReport.get();
        Map<String, Object> out = new LinkedHashMap<>(report.toMap());
        out.putIfAbsent("status", report.overallStatus().name().toLowerCase(java.util.Locale.ROOT));
        ok(request, channel, out);
    }
}
