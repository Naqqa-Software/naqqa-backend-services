package com.naqqa.auth.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

public class Browser {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mvc;
    private final String ip;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();
    private boolean autoCsrf = true;
    private String bearer;

    public Browser(MockMvc mvc, String ip) {
        this.mvc = mvc;
        this.ip = ip;
    }

    public String ip() {
        return ip;
    }

    public Browser autoCsrf(boolean value) {
        this.autoCsrf = value;
        return this;
    }

    public Browser bearer(String token) {
        this.bearer = token;
        return this;
    }

    public Browser header(String name, String value) {
        if (value == null) {
            headers.remove(name);
        } else {
            headers.put(name, value);
        }
        return this;
    }

    public Browser clientId(String clientId) {
        return header("X-Client-Id", clientId);
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public Browser cookie(String name, String value) {
        if (value == null) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
        return this;
    }

    public Map<String, String> cookies() {
        return cookies;
    }

    public MvcResult get(String path) {
        return perform(MockMvcRequestBuilders.get(path));
    }

    public MvcResult delete(String path) {
        return perform(MockMvcRequestBuilders.delete(path));
    }

    public MvcResult post(String path, Object body) {
        return perform(withBody(MockMvcRequestBuilders.post(path), body));
    }

    public MvcResult patch(String path, Object body) {
        return perform(withBody(MockMvcRequestBuilders.patch(path), body));
    }

    public MvcResult put(String path, Object body) {
        return perform(withBody(MockMvcRequestBuilders.put(path), body));
    }

    static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder b, Object body) {
        if (body == null) {
            return b;
        }
        try {
            String json = body instanceof String s ? s : MAPPER.writeValueAsString(body);
            return b.contentType(MediaType.APPLICATION_JSON).content(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public MvcResult perform(MockHttpServletRequestBuilder builder) {
        builder.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
        if (!cookies.isEmpty()) {
            builder.cookie(cookies.entrySet().stream().map(e -> new Cookie(e.getKey(), e.getValue())).toArray(Cookie[]::new));
        }
        headers.forEach(builder::header);
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        MvcResult result;
        try {
            MockHttpServletRequestBuilder b = builder;
            if (autoCsrf && cookies.containsKey("XSRF-TOKEN")) {
                b.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            result = mvc.perform(b).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        absorb(result.getResponse());
        return result;
    }

    void absorb(MockHttpServletResponse response) {
        for (String header : response.getHeaders("Set-Cookie")) {
            String[] parts = header.split(";");
            String[] kv = parts[0].split("=", 2);
            String name = kv[0].trim();
            String value = kv.length > 1 ? kv[1].trim() : "";
            boolean expired = false;
            for (String p : parts) {
                if (p.trim().equalsIgnoreCase("Max-Age=0")) {
                    expired = true;
                }
            }
            if (expired || value.isEmpty()) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }

    public static JsonNode json(MvcResult result) {
        try {
            String body = result.getResponse().getContentAsString();
            return body.isEmpty() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }
}
