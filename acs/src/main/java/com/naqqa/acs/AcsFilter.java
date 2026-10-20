package com.naqqa.acs;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class AcsFilter implements Filter {

    public static final class Rule {
        final String method;
        final Pattern path;
        final String action;
        final boolean signed;

        private Rule(String method, String glob, String action, boolean signed) {
            this.method = method == null ? null : method.toUpperCase();
            this.path = Pattern.compile(globToRegex(glob));
            this.action = action;
            this.signed = signed;
        }

        public static Rule sensitive(String method, String glob, String action) {
            return new Rule(method, glob, action, false);
        }

        public static Rule signed(String method, String glob) {
            return new Rule(method, glob, null, true);
        }

        boolean matches(String m, String p) {
            return (method == null || method.equals(m)) && path.matcher(p).matches();
        }
    }

    public static final class Options {
        public final List<String> trustedProxies;
        public final boolean strictIpBinding;

        public Options(List<String> trustedProxies, boolean strictIpBinding) {
            this.trustedProxies = List.copyOf(trustedProxies);
            this.strictIpBinding = strictIpBinding;
        }

        public static Options defaults() {
            return new Options(List.of("127.0.0.1/32", "::1/128"), false);
        }
    }

    public static final String ATTR_BINDING = "acs.binding";

    private final AcsVerifier verifier;
    private final List<Rule> rules;
    private final LongSupplier clock;
    private final Options options;

    public AcsFilter(AcsVerifier verifier, List<Rule> rules, LongSupplier clock) {
        this(verifier, rules, clock, Options.defaults());
    }

    public AcsFilter(AcsVerifier verifier, List<Rule> rules, LongSupplier clock, Options options) {
        this.verifier = verifier;
        this.rules = new ArrayList<>(rules);
        this.clock = clock;
        this.options = options;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String method = req.getMethod().toUpperCase();
        String path = req.getRequestURI();
        String ctx = req.getContextPath() == null ? "" : req.getContextPath();
        String rel = !ctx.isEmpty() && path.startsWith(ctx) ? path.substring(ctx.length()) : path;
        PathCanon canon = PathCanon.of(rel);
        if (canon.ambiguous || PathCanon.hasDotSegment(rel)) {
            res.setStatus(400);
            res.setHeader("cache-control", "no-store");
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"bad_request\"}");
            return;
        }
        Rule rule = null;
        for (Rule r : rules) {
            if (r.matches(method, canon.path)) {
                rule = r;
                break;
            }
        }
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }
        long now = clock.getAsLong();
        AcsVerifier.Result viaGuard = verifier.verifyGuardAssertion(req.getHeader("x-acs-guard"), method, path, req.getQueryString(), now);
        if (viaGuard.ok && !"block".equals(viaGuard.payload.get("tier"))) {
            chain.doFilter(request, response);
            return;
        }
        String token = req.getHeader("x-acs-token");
        if (token == null) token = cookie(req, "acs_t");
        String clientIp = IpUtil.resolveClientIp(req.getRemoteAddr(), req.getHeader("x-forwarded-for"), options.trustedProxies);
        AcsVerifier.Result att = token == null
                ? AcsVerifier.Result.fail("missing")
                : verifier.verifyAttestationBound(token, now, req.getHeader("user-agent"), clientIp, options.strictIpBinding);
        if (!att.ok) {
            deny(res, 401, "attest");
            return;
        }
        if ("ip-class-changed".equals(att.reason)) req.setAttribute(ATTR_BINDING, "ip-class-changed");
        String sub = (String) att.payload.get("sub");
        if (rule.action != null) {
            String act = req.getHeader("x-acs-action");
            AcsVerifier.Result a = act == null ? AcsVerifier.Result.fail("missing") : verifier.verifyAction(act, now, rule.action, sub);
            if (!a.ok) {
                deny(res, 401, "action");
                return;
            }
        }
        if (rule.signed) {
            AcsVerifier.Result s = verifier.verifySessionSig(sub, req.getHeader("x-acs-sig"), method, path, req.getQueryString(), now);
            if (!s.ok) {
                deny(res, 401, "attest");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void deny(HttpServletResponse res, int status, String challenge) throws IOException {
        res.setStatus(status);
        res.setHeader("x-acs-challenge", challenge);
        res.setHeader("cache-control", "no-store");
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"challenge\",\"challenge\":\"" + challenge + "\"}");
    }

    private static String cookie(HttpServletRequest req, String name) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return null;
        for (Cookie c : cs) if (name.equals(c.getName())) return c.getValue();
        return null;
    }

    static String globToRegex(String glob) {
        StringBuilder re = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else re.append("[^/]*");
            } else if ("\\^$+?.()|{}[]".indexOf(c) >= 0) re.append('\\').append(c);
            else re.append(c);
        }
        return re.append('$').toString();
    }
}
