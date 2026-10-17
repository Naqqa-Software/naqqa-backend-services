package com.naqqa.auth.sso.scim;

import com.naqqa.auth.sso.admin.SsoTenantQuota;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.support.SsoWeb;
import com.naqqa.auth.sso.web.filter.ApiTokenAuthentication;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.scim.ListQuery;
import com.naqqa.sso.scim.ScimErrors;
import com.naqqa.sso.scim.ScimJson;
import com.naqqa.sso.scim.ScimResponse;
import com.naqqa.sso.scim.ScimSchemas;
import com.naqqa.sso.tokens.ApiTokenPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

@RestController
@RequestMapping("/scim/v2")
@ConditionalOnProperty(prefix = "naqqa.sso", name = {"enabled", "scim.enabled"}, havingValue = "true")
public class ScimController {

    public static final String SCOPE = "scim";
    static final MediaType SCIM_JSON = MediaType.parseMediaType("application/scim+json");

    private final ScimEndpoint endpoint;
    private final SsoAudit audit;
    private final ObjectProvider<ScimAccountSync> accounts;
    private final ObjectProvider<SsoTenantQuota> quota;

    public ScimController(ScimEndpoint endpoint, SsoAudit audit, ObjectProvider<ScimAccountSync> accounts,
                          ObjectProvider<SsoTenantQuota> quota) {
        this.endpoint = endpoint;
        this.audit = audit;
        this.accounts = accounts;
        this.quota = quota;
    }

    private ScimResponse createUser(String tenant, Map<String, Object> body) {
        return synced(tenant, endpoint.onUser(s -> s.createUser(tenant, body)));
    }

    private ScimResponse synced(String tenant, ScimResponse r) {
        ScimAccountSync sync = accounts.getIfAvailable();
        if (sync != null && (r.status() == 200 || r.status() == 201) && r.body() != null && r.body().get("id") instanceof String id) {
            sync.sync(tenant, id);
        }
        return r;
    }

    record Caller(ApiTokenPrincipal principal, ResponseEntity<String> denied) {
    }

    static ResponseEntity<String> error(int status, String scimType, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(ScimSchemas.ERROR));
        if (scimType != null) {
            body.put("scimType", scimType);
        }
        body.put("detail", detail);
        body.put("status", Integer.toString(status));
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status).contentType(SCIM_JSON);
        if (status == 401) {
            b.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"scim\"");
        }
        return b.body(ScimJson.write(new ScimResponse(status, body, Map.of())));
    }

    static Caller caller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof ApiTokenAuthentication token)) {
            return new Caller(null, error(401, null, "API token required"));
        }
        if (!token.getPrincipal().scopes().contains(SCOPE)) {
            return new Caller(null, error(403, null, "API token lacks the scim scope"));
        }
        return new Caller(token.getPrincipal(), null);
    }

    static ResponseEntity<String> toEntity(ScimResponse r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.status());
        r.headers().forEach(b::header);
        String body = ScimJson.write(r);
        if (body.isEmpty()) {
            return b.build();
        }
        return b.contentType(SCIM_JSON).body(body);
    }

    private ResponseEntity<String> run(HttpServletRequest request, String action, Function<String, ScimResponse> call) {
        Caller c = caller();
        if (c.denied() != null) {
            return c.denied();
        }
        SsoTenantQuota q = quota.getIfAvailable();
        SsoTenantQuota.Outcome outcome = q == null ? null : q.scimOperation(c.principal().tenantId());
        if (outcome != null && outcome.blocked()) {
            ResponseEntity<String> e = error(429, null, "SCIM operation quota exceeded, retry later");
            return ResponseEntity.status(429).contentType(SCIM_JSON)
                    .header(HttpHeaders.RETRY_AFTER, Long.toString(outcome.retryAfterSeconds())).body(e.getBody());
        }
        ScimResponse r = call.apply(c.principal().tenantId());
        if (!"read".equals(action)) {
            audit.record("scim." + action, "api:" + c.principal().id(), c.principal().tenantId(),
                    r.status() < 400 ? "success" : "failure", Map.of("status", Integer.toString(r.status())), SsoWeb.clientIp(request));
        }
        return toEntity(r);
    }

    private ResponseEntity<String> guarded(String id, HttpServletRequest request, String action, Function<String, ScimResponse> call) {
        return run(request, action, tenant -> {
            ScimAccountSync sync = accounts.getIfAvailable();
            if (sync != null && sync.protectedAccount(tenant, id)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("schemas", List.of(ScimSchemas.ERROR));
                body.put("detail", "This account is protected and cannot be changed through SCIM");
                body.put("status", "403");
                return new ScimResponse(403, body, Map.of());
            }
            return call.apply(tenant);
        });
    }

    private ResponseEntity<String> withBody(HttpServletRequest request, String action, byte[] body,
                                            BiFunction<String, Map<String, Object>, ScimResponse> call) {
        return run(request, action, tenant -> ScimJson.parseOrError(body, parsed -> call.apply(tenant, parsed)));
    }

    private ScimResponse search(String tenant, boolean user, Map<String, Object> body) {
        ListQuery q;
        try {
            q = ListQuery.fromBody(body);
        } catch (SsoException e) {
            return ScimErrors.of(e);
        }
        return endpoint.list(tenant, user, q);
    }

    private ScimResponse list(String tenant, boolean user, Map<String, String> params) {
        ListQuery q;
        try {
            q = ListQuery.fromParams(params);
        } catch (SsoException e) {
            return ScimErrors.of(e);
        }
        return endpoint.list(tenant, user, q);
    }

    @GetMapping("/Users")
    public ResponseEntity<String> listUsers(@RequestParam Map<String, String> params, HttpServletRequest request) {
        return run(request, "read", t -> list(t, true, params));
    }

    @PostMapping("/Users/.search")
    public ResponseEntity<String> searchUsers(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return run(request, "read", t -> ScimJson.parseOrError(body, parsed -> search(t, true, parsed)));
    }

    @PostMapping("/Users")
    public ResponseEntity<String> createUser(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return withBody(request, "user.create", body, this::createUser);
    }

    @GetMapping("/Users/{id}")
    public ResponseEntity<String> getUser(@PathVariable String id, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String inm,
                                          HttpServletRequest request) {
        return run(request, "read", t -> endpoint.onUser(s -> s.getUser(t, id, inm)));
    }

    @PutMapping("/Users/{id}")
    public ResponseEntity<String> replaceUser(@PathVariable String id, @RequestBody(required = false) byte[] body,
                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                              HttpServletRequest request) {
        return guarded(id, request, "user.replace", t -> ScimJson.parseOrError(body, b -> synced(t, endpoint.onUser(s -> s.replaceUser(t, id, b, ifMatch)))));
    }

    @PatchMapping("/Users/{id}")
    public ResponseEntity<String> patchUser(@PathVariable String id, @RequestBody(required = false) byte[] body,
                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                            HttpServletRequest request) {
        return guarded(id, request, "user.patch", t -> ScimJson.parseOrError(body, b -> synced(t, endpoint.onUser(s -> s.patchUser(t, id, b, ifMatch)))));
    }

    @DeleteMapping("/Users/{id}")
    public ResponseEntity<String> deleteUser(@PathVariable String id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                             HttpServletRequest request) {
        return guarded(id, request, "user.delete", t -> endpoint.onUser(s -> s.deleteUser(t, id, ifMatch)));
    }

    @GetMapping("/Groups")
    public ResponseEntity<String> listGroups(@RequestParam Map<String, String> params, HttpServletRequest request) {
        return run(request, "read", t -> list(t, false, params));
    }

    @PostMapping("/Groups/.search")
    public ResponseEntity<String> searchGroups(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return run(request, "read", t -> ScimJson.parseOrError(body, parsed -> search(t, false, parsed)));
    }

    @PostMapping("/Groups")
    public ResponseEntity<String> createGroup(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        return withBody(request, "group.create", body, (t, b) -> endpoint.onGroup(s -> s.createGroup(t, b)));
    }

    @GetMapping("/Groups/{id}")
    public ResponseEntity<String> getGroup(@PathVariable String id, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String inm,
                                           HttpServletRequest request) {
        return run(request, "read", t -> endpoint.onGroup(s -> s.getGroup(t, id, inm)));
    }

    @PutMapping("/Groups/{id}")
    public ResponseEntity<String> replaceGroup(@PathVariable String id, @RequestBody(required = false) byte[] body,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               HttpServletRequest request) {
        return withBody(request, "group.replace", body, (t, b) -> endpoint.onGroup(s -> s.replaceGroup(t, id, b, ifMatch)));
    }

    @PatchMapping("/Groups/{id}")
    public ResponseEntity<String> patchGroup(@PathVariable String id, @RequestBody(required = false) byte[] body,
                                             @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                             HttpServletRequest request) {
        return withBody(request, "group.patch", body, (t, b) -> endpoint.onGroup(s -> s.patchGroup(t, id, b, ifMatch)));
    }

    @DeleteMapping("/Groups/{id}")
    public ResponseEntity<String> deleteGroup(@PathVariable String id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                              HttpServletRequest request) {
        return run(request, "group.delete", t -> endpoint.onGroup(s -> s.deleteGroup(t, id, ifMatch)));
    }

    @GetMapping("/ServiceProviderConfig")
    public ResponseEntity<String> serviceProviderConfig(HttpServletRequest request) {
        return run(request, "read", t -> endpoint.onUser(s -> s.serviceProviderConfig(t)));
    }

    @GetMapping("/ResourceTypes")
    public ResponseEntity<String> resourceTypes(HttpServletRequest request) {
        return run(request, "read", t -> endpoint.onUser(s -> s.resourceTypes(t)));
    }

    @GetMapping("/ResourceTypes/{id}")
    public ResponseEntity<String> resourceType(@PathVariable String id, HttpServletRequest request) {
        return run(request, "read", t -> endpoint.onUser(s -> s.resourceType(t, id)));
    }

    @GetMapping("/Schemas")
    public ResponseEntity<String> schemas(HttpServletRequest request) {
        return run(request, "read", t -> endpoint.onUser(s -> s.schemas(t)));
    }

    @GetMapping("/Schemas/{id}")
    public ResponseEntity<String> schema(@PathVariable String id, HttpServletRequest request) {
        return run(request, "read", t -> endpoint.onUser(s -> s.schema(t, id)));
    }
}
