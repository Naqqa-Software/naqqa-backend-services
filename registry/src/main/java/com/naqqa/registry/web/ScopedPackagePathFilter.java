package com.naqqa.registry.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScopedPackagePathFilter extends OncePerRequestFilter {

    static final Pattern ENCODED_SCOPE = Pattern.compile("^(@[a-z0-9][a-z0-9._-]*)%2[fF]([a-z0-9][a-z0-9._-]*)(/.*)?$");

    private final String npmPath;

    public ScopedPackagePathFilter(String npmPath) {
        String path = npmPath;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        this.npmPath = path + "/";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rewritten = rewrite(request.getRequestURI(), request.getContextPath());
        chain.doFilter(rewritten == null ? request : new RewrittenRequest(request, rewritten), response);
    }

    String rewrite(String uri, String contextPath) {
        String prefix = (contextPath == null ? "" : contextPath) + npmPath;
        if (uri == null || !uri.startsWith(prefix)) {
            return null;
        }
        Matcher m = ENCODED_SCOPE.matcher(uri.substring(prefix.length()));
        if (!m.matches()) {
            return null;
        }
        return prefix + m.group(1) + "/" + m.group(2) + (m.group(3) == null ? "" : m.group(3));
    }

    static final class RewrittenRequest extends HttpServletRequestWrapper {

        private final String uri;

        RewrittenRequest(HttpServletRequest request, String uri) {
            super(request);
            this.uri = uri;
        }

        @Override
        public String getRequestURI() {
            return uri;
        }

        @Override
        public StringBuffer getRequestURL() {
            HttpServletRequest request = (HttpServletRequest) getRequest();
            StringBuffer url = new StringBuffer();
            url.append(request.getScheme()).append("://").append(request.getServerName());
            int port = request.getServerPort();
            boolean defaultPort = ("http".equals(request.getScheme()) && port == 80) || ("https".equals(request.getScheme()) && port == 443);
            if (!defaultPort && port > 0) {
                url.append(':').append(port);
            }
            return url.append(uri);
        }

        @Override
        public String getServletPath() {
            String contextPath = getContextPath();
            return contextPath == null || contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
        }

        @Override
        public String getPathInfo() {
            return null;
        }
    }
}
