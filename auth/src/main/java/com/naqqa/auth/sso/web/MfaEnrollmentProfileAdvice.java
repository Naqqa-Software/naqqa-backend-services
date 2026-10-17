package com.naqqa.auth.sso.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.auth.security.NaqqaAuthenticationToken;
import com.naqqa.auth.sso.flow.MfaEnrollmentGate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class MfaEnrollmentProfileAdvice implements ResponseBodyAdvice<Object> {

    public static final String FLAG = "mfaEnrollmentRequired";

    private final MfaEnrollmentGate gate;
    private final ObjectMapper objectMapper;

    public MfaEnrollmentProfileAdvice(MfaEnrollmentGate gate, ObjectMapper objectMapper) {
        this.gate = gate;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || !(request instanceof ServletServerHttpRequest servlet)) {
            return body;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof NaqqaAuthenticationToken token) || !gate.flagsProfile(token.getPrincipal(), servlet.getServletRequest())) {
            return body;
        }
        Map<String, Object> out;
        if (body instanceof Map<?, ?> map) {
            out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
        } else {
            try {
                out = objectMapper.convertValue(body, objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            } catch (IllegalArgumentException e) {
                return body;
            }
        }
        out.put(FLAG, true);
        return out;
    }
}
