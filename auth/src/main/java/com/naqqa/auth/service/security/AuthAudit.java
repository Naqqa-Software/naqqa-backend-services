package com.naqqa.auth.service.security;

import com.naqqa.auth.sso.flow.SsoAudit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Slf4j
@Component
public class AuthAudit {

    private final ObjectProvider<SsoAudit> audit;

    public AuthAudit(ObjectProvider<SsoAudit> audit) {
        this.audit = audit;
    }

    public static String join(Collection<String> values) {
        return values == null ? "" : String.join(",", new TreeSet<>(values));
    }

    public static Map<String, String> change(String beforeKey, Collection<String> before, String afterKey, Collection<String> after) {
        Map<String, String> out = new LinkedHashMap<>();
        TreeSet<String> added = new TreeSet<>(after == null ? Set.of() : after);
        TreeSet<String> removed = new TreeSet<>(before == null ? Set.of() : before);
        if (before != null) {
            added.removeAll(before);
        }
        if (after != null) {
            removed.removeAll(after);
        }
        out.put("added", join(added));
        out.put("removed", join(removed));
        out.put(beforeKey, join(before));
        out.put(afterKey, join(after));
        return out;
    }

    static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth instanceof AnonymousAuthenticationToken ? "system" : auth.getName();
    }

    public void record(String action, String target, Map<String, String> attributes) {
        record(action, actor(), target, attributes);
    }

    public void record(String action, String actor, String target, Map<String, String> attributes) {
        SsoAudit sink = audit.getIfAvailable();
        if (sink != null) {
            sink.record(action, actor, target, "success", attributes);
            return;
        }
        log.info("audit action={} actor={} target={} attributes={}", action, actor, target, attributes);
    }
}
