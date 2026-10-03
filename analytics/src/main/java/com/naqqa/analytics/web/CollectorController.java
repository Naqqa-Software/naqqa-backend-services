package com.naqqa.analytics.web;

import com.naqqa.analytics.collect.CollectorService;
import com.naqqa.analytics.collect.CollectorService.CollectRequest;
import com.naqqa.analytics.collect.CollectorService.CollectResult;
import com.naqqa.analytics.collect.IpAnonymizer;
import com.naqqa.analytics.config.NaqqaAnalyticsProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class CollectorController {

    private final CollectorService collector;
    private final NaqqaAnalyticsProperties properties;

    public CollectorController(CollectorService collector, NaqqaAnalyticsProperties properties) {
        this.collector = collector;
        this.properties = properties;
    }

    @PostMapping(path = "${naqqa.analytics.collect-path:/t/e}", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> collect(HttpServletRequest request) throws IOException {
        int max = properties.getLimits().getMaxBodyBytes();
        if (request.getContentLengthLong() > max) {
            return error(413, "payload_too_large", 0);
        }
        byte[] body = read(request.getInputStream(), max + 1);
        String ip = IpAnonymizer.clientIp(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), request.getHeader("X-Real-IP"),
                properties.getBots().isTrustProxyHeaders());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        CollectResult result = collector.collect(new CollectRequest(body, ip, request.getHeader(HttpHeaders.USER_AGENT), auth,
                request.getServerName()));
        if (result.status() != 202) {
            return error(result.status(), result.code(), result.retryAfterSeconds());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", result.accepted());
        out.put("rejected", result.rejected());
        out.put("sid", result.sid());
        return ResponseEntity.status(202).header(HttpHeaders.CACHE_CONTROL, "no-store").body(out);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, long retryAfter) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("code", code);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (retryAfter > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        }
        return builder.body(out);
    }

    static byte[] read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            int take = Math.min(n, limit - total);
            out.write(buf, 0, take);
            total += take;
            if (total >= limit) {
                break;
            }
        }
        return out.toByteArray();
    }
}
