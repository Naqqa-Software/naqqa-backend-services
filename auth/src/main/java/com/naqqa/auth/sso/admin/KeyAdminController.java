package com.naqqa.auth.sso.admin;

import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.support.SsoApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping(SsoAdminAuthorities.BASE + "/keys")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
@PreAuthorize("hasAuthority('" + SsoAdminAuthorities.KEYS + "')")
public class KeyAdminController {

    private final SsoKeyRotation rotation;
    private final SsoAdminScope scope;
    private final SsoAudit audit;

    public KeyAdminController(SsoKeyRotation rotation, SsoAdminScope scope, SsoAudit audit) {
        this.rotation = rotation;
        this.scope = scope;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> list() {
        scope.userScope();
        return Map.of("rings", rotation.view());
    }

    @PostMapping("/{ring}/rotate")
    @RequiresStepUp
    public Map<String, Object> rotate(@PathVariable String ring, @RequestBody(required = false) SsoAdminRequests.KeyRotate body) {
        String adminId = scope.adminId();
        scope.userScope();
        if (!SsoKeyStore.RINGS.contains(ring)) {
            throw SsoApiException.notFound("NOT_FOUND", "Key ring not found");
        }
        Duration grace = body == null || body.graceSeconds() == null ? SsoKeyRotation.DEFAULT_GRACE : Duration.ofSeconds(body.graceSeconds());
        if (grace.isNegative() || grace.compareTo(SsoKeyRotation.MAX_GRACE) > 0) {
            throw SsoApiException.badRequest("GRACE_INVALID", "graceSeconds must be between 0 and 604800");
        }
        SsoKeyRotation.Rotation r = rotation.rotate(ring, grace);
        audit.record("admin.keys.rotate", adminId, ring, "success", Map.of("kid", r.kid(),
                "previousKid", r.previousKid() == null ? "" : r.previousKid(), "grace", grace.toString()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ring", r.ring());
        out.put("kid", r.kid());
        out.put("previousKid", r.previousKid());
        out.put("previousNotAfter", r.previousNotAfter());
        return out;
    }
}
