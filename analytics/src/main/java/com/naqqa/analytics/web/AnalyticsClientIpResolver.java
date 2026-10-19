package com.naqqa.analytics.web;

import com.naqqa.analytics.config.AnalyticsProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the visitor IP for geo, the visitor hash and the optional raw IP. {@code X-Real-IP} /
 * {@code X-Forwarded-For} are honoured only when the direct peer is one of {@code naqqa.analytics.trusted-proxies};
 * from any other peer they are client controlled, so a beacon could pick any address (fake geo, forged visitors,
 * arbitrary geo lookups).
 */
@Component
public class AnalyticsClientIpResolver {

    private final List<IpAddressMatcher> trusted;

    public AnalyticsClientIpResolver(AnalyticsProperties props) {
        List<IpAddressMatcher> out = new ArrayList<>();
        String value = props.getTrustedProxies();
        for (String entry : (value == null ? "" : value).split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            try {
                out.add(new IpAddressMatcher(e));
            } catch (IllegalArgumentException ignored) {
                // invalid entry: skipped
            }
        }
        this.trusted = List.copyOf(out);
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isTrusted(remote)) {
            return remote;
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            // The last hop is the one the trusted proxy appended (the address it saw).
            return hops[hops.length - 1].trim();
        }
        return remote;
    }

    private boolean isTrusted(String remote) {
        if (remote == null) {
            return false;
        }
        for (IpAddressMatcher m : trusted) {
            try {
                if (m.matches(remote)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // address family mismatch
            }
        }
        return false;
    }
}
