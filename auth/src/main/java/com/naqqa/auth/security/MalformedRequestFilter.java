package com.naqqa.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

public class MalformedRequestFilter extends OncePerRequestFilter {

    public static final String CODE = "INVALID_REQUEST";

    private final Supplier<List<SecurityFilterChain>> chains;
    private volatile List<SecurityFilterChain> resolved;

    public MalformedRequestFilter(Supplier<List<SecurityFilterChain>> chains) {
        this.chains = chains;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        List<SecurityFilterChain> list = resolved;
        if (list == null) {
            list = chains.get();
            resolved = list;
        }
        for (SecurityFilterChain chain : list) {
            if (chain.matches(request)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            if (response.isCommitted() || !malformed(e)) {
                throw e;
            }
            response.resetBuffer();
            JsonSecurityHandlers.write(response, HttpServletResponse.SC_BAD_REQUEST, CODE, "Malformed request");
        }
    }

    static boolean malformed(Throwable e) {
        Throwable t = e;
        for (int depth = 0; t != null && depth < 8; depth++) {
            if (t instanceof IllegalArgumentException || t instanceof HttpMessageNotReadableException
                    || t instanceof HttpMediaTypeException || t instanceof MultipartException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
