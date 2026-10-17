package com.naqqa.auth.support;

import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.sso.mfa.StepUp;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

@RestController
@RequestMapping("/api/auth/test")
public class TestProtectedController {

    static Map<String, Object> describe() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", auth == null ? null : auth.getName());
        out.put("authorities", auth == null ? null : new TreeSet<>(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList()));
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal p) {
            out.put("sub", p.getSubject());
            out.put("email", p.getEmail());
            out.put("sid", p.getSessionId());
            out.put("amr", p.getAmr());
            out.put("acr", p.getAcr());
            out.put("act", p.getActor());
        }
        return out;
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('profile:read')")
    public Map<String, Object> me() {
        return describe();
    }

    @GetMapping("/whoami")
    public Map<String, Object> whoami() {
        return describe();
    }

    @GetMapping("/fresh")
    @RequiresStepUp
    public Map<String, Object> fresh() {
        return describe();
    }

    @GetMapping("/fresh-mfa")
    @RequiresStepUp(acr = StepUp.ACR_MFA)
    public Map<String, Object> freshMfa() {
        return describe();
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('SCOPE_reports')")
    public Map<String, Object> reports() {
        return describe();
    }

    @PostMapping("/mutate")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> mutate() {
        return describe();
    }
}
