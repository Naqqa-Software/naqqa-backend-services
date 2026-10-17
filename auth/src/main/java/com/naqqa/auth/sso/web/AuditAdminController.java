package com.naqqa.auth.sso.web;

import com.naqqa.auth.sso.admin.SsoAdminScope;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.support.SsoWeb;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping({"${auth.api.base-path:/api/auth}/admin/audit", "/api/sso/admin/audit"})
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
@PreAuthorize("hasAuthority('" + AuditAdminController.AUTHORITY + "')")
public class AuditAdminController {

    static final int MAX_EXPORT = 100_000;

    public static final String AUTHORITY = "SSO_AUDIT";
    public static final String EXPORT_AUTHORITY = "SSO_AUDIT_EXPORT";

    private final SsoAudit audit;
    private final ObjectMapper mapper;
    private final SsoAdminScope scope;

    @GetMapping("/verify")
    public Map<String, Object> verify(@RequestParam(defaultValue = "false") boolean full) {
        scope.userScope();
        return verification(audit.verify(full));
    }

    @PostMapping("/verify")
    public Map<String, Object> verifyFull(@RequestParam(defaultValue = "true") boolean full) {
        scope.userScope();
        return verification(audit.verify(full));
    }

    @GetMapping(value = "/export", produces = "application/x-ndjson")
    @PreAuthorize("hasAuthority('" + EXPORT_AUTHORITY + "')")
    public ResponseEntity<String> export(@RequestParam(defaultValue = "0") long from, @RequestParam(defaultValue = "10000") int limit,
                                         @RequestParam(defaultValue = "true") boolean full) throws JsonProcessingException {
        scope.userScope();
        audit.checkpoint();
        SsoAudit.Export export = audit.export(Math.max(0, from), Math.min(Math.max(1, limit), MAX_EXPORT), full);
        audit.record("audit.export", String.valueOf(SsoWeb.requirePrincipal().getUserId()), null, "success",
                Map.of("count", Long.toString(export.count())));
        Map<String, Object> trailer = new LinkedHashMap<>();
        trailer.put("count", export.count());
        trailer.put("firstSeq", export.firstSeq());
        trailer.put("lastSeq", export.lastSeq());
        SsoAudit.Anchor anchor = export.anchor();
        if (anchor != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("seq", anchor.seq());
            a.put("hash", anchor.hash());
            a.put("at", anchor.at().toString());
            a.put("keyId", anchor.keyId());
            a.put("signature", anchor.signature());
            trailer.put("purgeAnchor", a);
        }
        trailer.put("verify", verification(export.verification()));
        String body = export.lines() + mapper.writeValueAsString(Map.of("export", trailer)) + "\n";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-ndjson")).body(body);
    }

    private static Map<String, Object> verification(SsoAudit.Verification v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", v.ok());
        out.put("reason", v.reason());
        out.put("badSeq", v.badSeq());
        out.put("checkedFrom", v.checkedFrom());
        out.put("checkedTo", v.checkedTo());
        out.put("checkpoint", v.checkpointSeq());
        return out;
    }

    @PostMapping("/checkpoint")
    public Map<String, Object> checkpoint() {
        scope.userScope();
        Map<String, Object> out = new LinkedHashMap<>();
        audit.checkpoint().ifPresent(cp -> {
            out.put("seq", cp.seq());
            out.put("head", cp.head());
            out.put("at", cp.at());
        });
        return out;
    }
}
