package com.naqqa.elasticsearch.node.security;

import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.security.audit.AuditLogger;
import com.naqqa.elasticsearch.security.audit.AuditSettings;
import com.naqqa.elasticsearch.security.audit.AuditSink;
import com.naqqa.elasticsearch.security.authc.ApiKeyService;
import com.naqqa.elasticsearch.security.authc.Authentication;
import com.naqqa.elasticsearch.security.authc.AuthenticationResult;
import com.naqqa.elasticsearch.security.authc.BasicAuthHeader;
import com.naqqa.elasticsearch.security.authc.FileRealm;
import com.naqqa.elasticsearch.security.authc.InMemorySecurityIndexStore;
import com.naqqa.elasticsearch.security.authc.NativeRealm;
import com.naqqa.elasticsearch.security.authc.PasswordHashers;
import com.naqqa.elasticsearch.security.authc.Realm;
import com.naqqa.elasticsearch.security.authc.RealmChain;
import com.naqqa.elasticsearch.security.authc.User;
import com.naqqa.elasticsearch.security.authc.UsernamePasswordToken;
import com.naqqa.elasticsearch.security.authz.AuthorizationResult;
import com.naqqa.elasticsearch.security.authz.Authorizer;
import com.naqqa.elasticsearch.security.authz.BuiltinRoles;
import com.naqqa.elasticsearch.security.authz.ClusterPrivilege;
import com.naqqa.elasticsearch.security.authz.IndexPrivilege;
import com.naqqa.elasticsearch.security.authz.IndicesPrivileges;
import com.naqqa.elasticsearch.security.authz.RoleDescriptor;
import com.naqqa.elasticsearch.node.support.SettingsMaps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SecurityService {

    public static final class AuthenticationException extends RestApiException {
        public AuthenticationException(String message) {
            super(401, message);
        }
    }

    public static final class AuthorizationException extends RestApiException {
        public AuthorizationException(String message) {
            super(403, message);
        }
    }

    public record RequestAction(String action, List<String> indices) {
    }

    private final boolean enabled;
    private final InMemorySecurityIndexStore nativeStore = new InMemorySecurityIndexStore();
    private final ApiKeyService apiKeyService = new ApiKeyService();
    private final RealmChain realmChain;
    private final Authorizer authorizer = new Authorizer();
    private final Map<String, RoleDescriptor> customRoles = new ConcurrentHashMap<>();
    private final AuditLogger auditLogger;
    private final List<String> realmNames = new ArrayList<>();

    public SecurityService(boolean enabled, Path configDir, Path auditFile, boolean auditEnabled, String bootstrapPassword) {
        this.enabled = enabled;
        List<Realm> realms = new ArrayList<>();
        Path usersFile = configDir == null ? null : configDir.resolve("users");
        Path usersRolesFile = configDir == null ? null : configDir.resolve("users_roles");
        if (usersFile != null && Files.exists(usersFile)) {
            try {
                Path roles = Files.exists(usersRolesFile) ? usersRolesFile : Files.createTempFile("users_roles", "");
                realms.add(FileRealm.loadFromFiles("file1", usersFile, roles));
                realmNames.add("file1");
            } catch (IOException e) {
                System.err.println("[security] failed to load file realm: " + e);
            }
        }
        realms.add(new NativeRealm("native1", nativeStore));
        realmNames.add("native1");
        this.realmChain = new RealmChain(realms);
        if (enabled) {
            String password = bootstrapPassword;
            if (password == null || password.isEmpty()) {
                password = System.getenv("ELASTIC_PASSWORD");
            }
            if (password != null && !password.isEmpty()) {
                putUser("elastic", password.toCharArray(), List.of("superuser"), "elastic", null);
            }
        }
        AuditSink sink = auditEnabled && auditFile != null ? line -> appendLine(auditFile, line) : line -> {
        };
        this.auditLogger = new AuditLogger(AuditSettings.allEvents(), sink);
    }

    private static synchronized void appendLine(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, (line + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public ApiKeyService apiKeyService() {
        return apiKeyService;
    }

    public void putUser(String username, char[] password, List<String> roles, String fullName, String email) {
        Map<String, Object> doc = new LinkedHashMap<>();
        Optional<Map<String, Object>> existing = nativeStore.getUser(username);
        if (password != null) {
            doc.put("password_hash", PasswordHashers.hashWithPbkdf2(password));
        } else if (existing.isPresent()) {
            doc.put("password_hash", existing.get().get("password_hash"));
        } else {
            throw new RestApiException(400, "password must be specified unless you are updating an existing user");
        }
        doc.put("roles", roles == null ? List.of() : roles);
        doc.put("full_name", fullName);
        doc.put("email", email);
        doc.put("enabled", true);
        doc.put("metadata", Map.of());
        nativeStore.putUser(username, doc);
    }

    public boolean deleteUser(String username) {
        if (nativeStore.getUser(username).isEmpty()) {
            return false;
        }
        nativeStore.removeUser(username);
        return true;
    }

    public Map<String, Object> getUsers() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : nativeStore.listUsernames()) {
            Map<String, Object> doc = nativeStore.getUser(name).orElse(Map.of());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("username", name);
            view.put("roles", doc.getOrDefault("roles", List.of()));
            view.put("full_name", doc.get("full_name"));
            view.put("email", doc.get("email"));
            view.put("enabled", doc.getOrDefault("enabled", true));
            out.put(name, view);
        }
        return out;
    }

    public void putRole(String name, Map<String, Object> body) {
        Set<ClusterPrivilege> cluster = EnumSet.noneOf(ClusterPrivilege.class);
        for (String p : SettingsMaps.asStringList(body.get("cluster"))) {
            try {
                cluster.add(ClusterPrivilege.valueOf(p.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new RestApiException(400, "unknown cluster privilege [" + p + "]");
            }
        }
        List<IndicesPrivileges> indices = new ArrayList<>();
        if (body.get("indices") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> spec = SettingsMaps.asMap(o);
                if (spec == null) {
                    continue;
                }
                Set<IndexPrivilege> privileges = EnumSet.noneOf(IndexPrivilege.class);
                for (String p : SettingsMaps.asStringList(spec.get("privileges"))) {
                    try {
                        privileges.add(IndexPrivilege.valueOf(p.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        throw new RestApiException(400, "unknown index privilege [" + p + "]");
                    }
                }
                indices.add(new IndicesPrivileges(SettingsMaps.asStringList(spec.get("names")), privileges,
                    SettingsMaps.asMap(spec.get("query")), null));
            }
        }
        customRoles.put(name, new RoleDescriptor(name, cluster, indices, SettingsMaps.asStringList(body.get("run_as"))));
    }

    public boolean deleteRole(String name) {
        return customRoles.remove(name) != null;
    }

    public Map<String, Object> getRoles() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RoleDescriptor r : customRoles.values()) {
            List<Object> indices = new ArrayList<>();
            for (IndicesPrivileges ip : r.indicesPrivileges()) {
                indices.add(Map.of("names", ip.indices(), "privileges",
                    ip.privileges().stream().map(p -> p.name().toLowerCase(Locale.ROOT)).toList()));
            }
            out.put(r.name(), Map.of("cluster", r.clusterPrivileges().stream().map(p -> p.name().toLowerCase(Locale.ROOT)).toList(),
                "indices", indices, "run_as", r.runAs()));
        }
        return out;
    }

    public List<RoleDescriptor> resolveRoles(List<String> names) {
        List<RoleDescriptor> out = new ArrayList<>();
        for (String name : names) {
            RoleDescriptor role = customRoles.get(name);
            if (role == null) {
                role = BuiltinRoles.byName(name);
            }
            if (role != null) {
                out.add(role);
            }
        }
        return out;
    }

    public Authentication authenticate(RestRequest request) {
        String header = request.header("Authorization");
        if (header == null || header.isBlank()) {
            auditLogger.authenticationFailed("_anonymous", "missing authentication credentials");
            throw new AuthenticationException("missing authentication credentials for REST request [" + request.path() + "]");
        }
        AuthenticationResult result;
        String principal;
        if (header.regionMatches(true, 0, "ApiKey ", 0, 7)) {
            principal = "_api_key";
            result = apiKeyService.authenticate("ApiKey " + header.substring(7).trim());
        } else {
            Optional<UsernamePasswordToken> token = BasicAuthHeader.parse(header);
            if (token.isEmpty()) {
                auditLogger.authenticationFailed("_unknown", "unsupported authorization scheme");
                throw new AuthenticationException("unable to authenticate: unsupported authorization scheme");
            }
            principal = token.get().username();
            result = realmChain.authenticate(token.get());
        }
        if (result == null || !result.isAuthenticated()) {
            String message = result == null || result.message() == null ? "unable to authenticate user [" + principal + "]"
                : result.message();
            auditLogger.authenticationFailed(principal, message);
            throw new AuthenticationException(message);
        }
        auditLogger.authenticationSuccess(result.user().username(), result.realmName());
        return Authentication.of(result.user(), result.realmName());
    }

    public void authorize(Authentication authentication, RequestAction action) {
        User user = authentication.effectiveUser();
        List<RoleDescriptor> roles = resolveRoles(user.roles());
        List<String> indices = action.action().startsWith("indices:") ? (action.indices().isEmpty() ? List.of("*") : action.indices())
            : List.of();
        AuthorizationResult result = authorizer.authorize(user.username(), roles, action.action(), indices);
        if (!result.allowed()) {
            auditLogger.accessDenied(user.username(), action.action(), indices);
            throw new AuthorizationException(result.reason());
        }
        auditLogger.accessGranted(user.username(), action.action(), indices);
    }

    public Map<String, Object> describe(Authentication authentication) {
        Map<String, Object> out = new LinkedHashMap<>();
        User user = authentication.effectiveUser();
        out.put("username", user.username());
        out.put("roles", user.roles());
        out.put("full_name", user.fullName());
        out.put("email", user.email());
        out.put("metadata", user.metadata() == null ? Map.of() : user.metadata());
        out.put("enabled", user.enabled());
        out.put("authentication_realm", Map.of("name", authentication.realmName(), "type",
            authentication.realmName() == null ? "unknown" : authentication.realmName().replaceAll("\\d+$", "")));
        out.put("lookup_realm", Map.of("name", authentication.realmName(), "type",
            authentication.realmName() == null ? "unknown" : authentication.realmName().replaceAll("\\d+$", "")));
        out.put("authentication_type", "realm");
        return out;
    }

    public static RequestAction actionFor(RestMethod method, String pattern, RestRequest request) {
        List<String> indices = new ArrayList<>();
        String indexParam = request.param("index");
        if (indexParam != null) {
            for (String s : indexParam.split(",")) {
                if (!s.isBlank()) {
                    indices.add(s.trim());
                }
            }
        }
        boolean read = method == RestMethod.GET || method == RestMethod.HEAD;
        String p = pattern;
        if (p.equals("/")) {
            return new RequestAction("cluster:monitor/main", List.of());
        }
        if (p.startsWith("/_security")) {
            if (p.startsWith("/_security/_authenticate")) {
                return new RequestAction("cluster:monitor/xpack/security/authenticate", List.of());
            }
            if (p.startsWith("/_security/api_key")) {
                return new RequestAction("cluster:admin/xpack/security/api_key/" + (read ? "get" : "manage"), List.of());
            }
            return new RequestAction("cluster:admin/xpack/security/" + (read ? "get" : "manage"), List.of());
        }
        if (p.startsWith("/_cluster") || p.startsWith("/_nodes") || p.startsWith("/_tasks") || p.startsWith("/_cat")) {
            if (p.startsWith("/_cat") || read) {
                return new RequestAction("cluster:monitor" + p.replace("{", "").replace("}", ""), List.of());
            }
            return new RequestAction("cluster:admin" + p.replace("{", "").replace("}", ""), List.of());
        }
        if (p.startsWith("/_snapshot")) {
            return new RequestAction(read ? "cluster:admin/snapshot/get" : "cluster:admin/snapshot/manage", List.of());
        }
        if (p.startsWith("/_ingest")) {
            return new RequestAction(read ? "cluster:monitor/ingest/pipeline/get" : "cluster:admin/ingest/pipeline", List.of());
        }
        if (p.startsWith("/_index_template") || p.startsWith("/_component_template") || p.startsWith("/_template")) {
            return new RequestAction("cluster:admin/index_template/" + (read ? "get" : "put"), List.of());
        }
        if (p.startsWith("/_ilm") || p.contains("/_ilm/")) {
            return new RequestAction("cluster:admin/ilm/" + (read ? "get" : "put"), List.of());
        }
        if (p.startsWith("/_data_stream")) {
            return new RequestAction("indices:admin/data_stream/" + (read ? "get" : "manage"), List.of(request.param("name") == null ? "*" : request.param("name")));
        }
        if (p.startsWith("/_scripts")) {
            return new RequestAction("cluster:admin/script/" + (read ? "get" : "put"), List.of());
        }
        boolean dataRead = p.contains("_search") || p.contains("_count") || p.contains("_msearch") || p.contains("_explain")
            || p.contains("_validate") || p.contains("_field_caps") || p.contains("_terms_enum") || p.contains("_rank_eval")
            || p.contains("_async_search") || p.contains("_pit") || p.contains("_mget") || p.contains("_termvectors")
            || p.contains("_mtermvectors") || p.contains("_source") || p.startsWith("/_render")
            || (p.contains("/_doc/") && read);
        if (dataRead) {
            return new RequestAction("indices:data/read/search", indices);
        }
        if (p.contains("_bulk")) {
            return new RequestAction("indices:data/write/bulk", indices);
        }
        if (p.contains("/_doc") || p.contains("_create") || p.contains("_update/")) {
            return new RequestAction(method == RestMethod.DELETE ? "indices:data/write/delete" : "indices:data/write/index", indices);
        }
        if (p.contains("_delete_by_query") || p.contains("_update_by_query") || p.startsWith("/_reindex")) {
            return new RequestAction("indices:data/write/by_query", indices);
        }
        if (p.contains("_stats") || p.contains("_segments") || p.contains("_recovery") || p.contains("_shard_stores")
            || p.contains("_disk_usage")) {
            return new RequestAction("indices:monitor/stats", indices);
        }
        if (p.contains("_mapping")) {
            return new RequestAction(read ? "indices:admin/mapping/get" : "indices:admin/mapping/put", indices);
        }
        if (p.contains("_alias")) {
            return new RequestAction(read ? "indices:admin/aliases/get" : "indices:admin/aliases", indices);
        }
        if (p.equals("/{index}")) {
            String action = switch (method) {
                case PUT -> "indices:admin/create";
                case DELETE -> "indices:admin/delete";
                default -> "indices:admin/get";
            };
            return new RequestAction(action, indices);
        }
        return new RequestAction(read ? "indices:admin/get" : "indices:admin/manage", indices);
    }
}
