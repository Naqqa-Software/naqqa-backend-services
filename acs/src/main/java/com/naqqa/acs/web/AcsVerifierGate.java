package com.naqqa.acs.web;

import com.naqqa.acs.AcsFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

public class AcsVerifierGate implements Filter {

    public static final String ATTR_REFUSED = "acs.verifier.refused";

    public static final Set<String> DEFAULT_FAIL_OPEN_PATHS = Set.of(
            "/api/auth/login", "/api/auth/register", "/api/auth/forgot-password", "/api/auth/reset-password");

    private final AtomicReference<AcsFilter> verifier;
    private final boolean enforce;
    private final BiPredicate<String, String> isProtected;
    private final Set<String> failOpenPaths;

    public AcsVerifierGate(AcsFilter verifier, boolean enforce) {
        this(verifier, enforce, (m, p) -> true);
    }

    public AcsVerifierGate(AcsFilter verifier, boolean enforce, BiPredicate<String, String> isProtected) {
        this(verifier, enforce, isProtected, DEFAULT_FAIL_OPEN_PATHS);
    }

    public AcsVerifierGate(AcsFilter verifier, boolean enforce, BiPredicate<String, String> isProtected, Set<String> failOpenPaths) {
        this.verifier = new AtomicReference<>(verifier);
        this.enforce = enforce;
        this.isProtected = isProtected;
        this.failOpenPaths = Set.copyOf(failOpenPaths);
    }

    public boolean enforcing() {
        return enforce;
    }

    public void setVerifier(AcsFilter next) {
        verifier.set(next);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        AcsFilter current = verifier.get();
        if (current == null) {
            keysUnavailable((HttpServletRequest) request, (HttpServletResponse) response, chain);
            return;
        }
        if (enforce) {
            current.doFilter(request, response, chain);
            return;
        }
        boolean[] passed = {false};
        Discard discard = new Discard((HttpServletResponse) response);
        try {
            current.doFilter(request, discard, (rq, rs) -> passed[0] = true);
        } catch (RuntimeException e) {
            passed[0] = true;
        }
        if (!passed[0]) {
            String challenge = discard.challenge != null ? discard.challenge : "status-" + discard.status;
            request.setAttribute(ATTR_REFUSED, challenge);
            ((HttpServletResponse) response).setHeader("x-acs-would", "challenge");
        }
        chain.doFilter(request, response);
    }

    private void keysUnavailable(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String path = AcsPaths.canonical(request.getRequestURI());
        if (!isProtected.test(method, path)) {
            chain.doFilter(request, response);
            return;
        }
        request.setAttribute(ATTR_REFUSED, "keys-unavailable");
        if (!enforce || failOpenPaths.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(503);
        response.setHeader("retry-after", "30");
        response.setHeader("cache-control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unavailable\"}");
    }

    private static final class Discard extends HttpServletResponseWrapper {
        int status = 200;
        String challenge;
        private final PrintWriter sink = new PrintWriter(new StringWriter());

        Discard(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            status = sc;
        }

        @Override
        public void setHeader(String name, String value) {
            if ("x-acs-challenge".equalsIgnoreCase(name)) {
                challenge = value;
            }
        }

        @Override
        public void addHeader(String name, String value) {
            setHeader(name, value);
        }

        @Override
        public void setContentType(String type) {
        }

        @Override
        public PrintWriter getWriter() {
            return sink;
        }
    }
}
