package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

public final class SafeHttp {
    public static final int MAX_RESPONSE_BYTES = 512 * 1024;
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    public static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_URL_LENGTH = 2048;
    public static final String LOOPBACK_TEST_HOST = "127.0.0.1";

    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public record Options(boolean testMode, Duration connectTimeout, Duration totalTimeout, int maxBytes, Resolver resolver) {
        public Options {
            if (connectTimeout == null || totalTimeout == null || resolver == null || maxBytes < 1) {
                throw new SsoException("oauth.http_options_invalid");
            }
        }

        public static Options production() {
            return new Options(false, CONNECT_TIMEOUT, TOTAL_TIMEOUT, MAX_RESPONSE_BYTES, InetAddress::getAllByName);
        }

        public static Options forTests() {
            return new Options(true, CONNECT_TIMEOUT, TOTAL_TIMEOUT, MAX_RESPONSE_BYTES, InetAddress::getAllByName);
        }

        public Options withTotalTimeout(Duration value) {
            return new Options(testMode, connectTimeout, value, maxBytes, resolver);
        }

        public Options withMaxBytes(int value) {
            return new Options(testMode, connectTimeout, totalTimeout, value, resolver);
        }

        public Options withResolver(Resolver value) {
            return new Options(testMode, connectTimeout, totalTimeout, maxBytes, value);
        }
    }

    public record Response(int status, byte[] body, String contentType, Map<String, String> headers) {
        public Response {
            body = body.clone();
            headers = Map.copyOf(headers);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        public String bodyUtf8() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final PinnedHttpClient client;
    private final Options options;

    public SafeHttp(Options options) {
        this(options, new PinnedHttpClient(options.connectTimeout(), options.totalTimeout(), options.maxBytes(), options.resolver()));
    }

    public SafeHttp(Options options, PinnedHttpClient client) {
        this.options = options;
        this.client = client;
    }

    public static SafeHttp production() {
        return new SafeHttp(Options.production());
    }

    public boolean testMode() {
        return options.testMode();
    }

    public URI checkUrl(String url) {
        URI uri = parseUrl(url);
        if (isLoopbackTest(uri)) {
            return uri;
        }
        String host = uri.getHost();
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses;
        try {
            addresses = options.resolver().resolve(bare);
        } catch (UnknownHostException | RuntimeException e) {
            throw new SsoException("oauth.dns_failed");
        }
        if (addresses == null || addresses.length == 0) {
            throw new SsoException("oauth.dns_failed");
        }
        for (InetAddress a : addresses) {
            if (IpGuard.isBlocked(a)) {
                throw new SsoException("oauth.ssrf_blocked");
            }
        }
        return uri;
    }

    private boolean isLoopbackTest(URI uri) {
        return options.testMode() && LOOPBACK_TEST_HOST.equals(uri.getHost());
    }

    private URI parseUrl(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH) {
            throw new SsoException("oauth.url_invalid");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new SsoException("oauth.url_invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new SsoException(scheme.isEmpty() ? "oauth.url_invalid" : "oauth.url_insecure");
        }
        if (host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new SsoException("oauth.url_invalid");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new SsoException("oauth.url_invalid");
        }
        boolean loopbackTest = options.testMode() && LOOPBACK_TEST_HOST.equals(host);
        if (scheme.equals("http")) {
            if (!loopbackTest) {
                throw new SsoException("oauth.url_insecure");
            }
        } else if (!scheme.equals("https")) {
            throw new SsoException("oauth.url_insecure");
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!loopbackTest && !host.startsWith("[") && !plausibleHost(bare)) {
            throw new SsoException("oauth.url_invalid");
        }
        return uri;
    }

    private static boolean plausibleHost(String host) {
        String last = host.substring(host.lastIndexOf('.') + 1);
        if (last.isEmpty() || !Character.isDigit(last.charAt(0))) {
            return true;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) {
                return false;
            }
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(p) > 255) {
                return false;
            }
        }
        return true;
    }

    public Response get(String url, Map<String, String> headers, Collection<String> contentTypes) {
        URI uri = parseUrl(url);
        return send("GET", uri, withDefaults(headers), null, contentTypes);
    }

    public Response postForm(String url, Map<String, String> form, Map<String, String> headers, Collection<String> contentTypes) {
        URI uri = parseUrl(url);
        Map<String, String> h = withDefaults(headers);
        h.put("Content-Type", "application/x-www-form-urlencoded");
        return send("POST", uri, h, formEncode(form).getBytes(StandardCharsets.UTF_8), contentTypes);
    }

    public static String formEncode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return sb.toString();
    }

    public static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Map<String, String> withDefaults(Map<String, String> headers) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "naqqa-sso/1");
        h.put("Accept", "application/json");
        if (headers != null) {
            h.putAll(headers);
        }
        return h;
    }

    private Response send(String method, URI uri, Map<String, String> requestHeaders, byte[] body, Collection<String> contentTypes) {
        PinnedHttpClient.Result r = client.execute(method, uri, requestHeaders, body, isLoopbackTest(uri));
        int status = r.status();
        if (status >= 300 && status < 400) {
            throw new SsoException("oauth.http_redirect");
        }
        Map<String, String> headers = r.headers();
        String ct = headers.get("content-type");
        if (status >= 200 && status < 300 && contentTypes != null && !contentTypes.isEmpty() && r.body().length > 0) {
            String base = ct == null ? "" : ct.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!contentTypes.contains(base)) {
                throw new SsoException("oauth.content_type_invalid");
            }
        }
        return new Response(status, r.body(), ct, headers);
    }
}
