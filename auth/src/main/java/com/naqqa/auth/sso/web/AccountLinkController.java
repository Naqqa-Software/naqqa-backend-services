package com.naqqa.auth.sso.web;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.RequiresStepUp;
import com.naqqa.auth.sso.flow.AccountLinkService;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.flow.SsoUsers;
import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.auth.sso.support.SsoWeb;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("${auth.api.base-path:/api/auth}/account/links")
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class AccountLinkController {

    private final AccountLinkService links;
    private final SsoUsers users;
    private final SsoClientPolicy clientPolicy;

    @GetMapping
    public List<Map<String, Object>> list() {
        UserEntity user = users.current();
        return links.list(user).stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", l.provider());
            m.put("email", l.email());
            m.put("linkedAt", l.linkedAt());
            return m;
        }).toList();
    }

    @PostMapping("/{provider}/start")
    @RequiresStepUp
    public Map<String, Object> start(@PathVariable String provider, HttpServletRequest request) {
        AuthPrincipal principal = SsoWeb.requireOwnSession();
        UserEntity user = users.require(principal);
        String p = provider.toLowerCase(Locale.ROOT);
        if (!clientPolicy.forRequest(request).social().contains(p)) {
            throw SsoApiException.disabled(p);
        }
        if (principal.getSessionId() == null) {
            throw SsoApiException.unauthorized("SESSION_REQUIRED", "Sign in again");
        }
        String intent = links.startIntent(String.valueOf(user.getId()), principal.getSessionId(), p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("linkIntent", intent);
        out.put("provider", p);
        return out;
    }

    @DeleteMapping("/{provider}")
    @RequiresStepUp
    public ResponseEntity<Void> unlink(@PathVariable String provider) {
        UserEntity user = users.currentOwn();
        if (!links.unlink(user, provider, SsoWeb.requirePrincipal().getSessionId())) {
            throw SsoApiException.notFound("NOT_FOUND", "No linked account for this provider");
        }
        return ResponseEntity.noContent().build();
    }
}
