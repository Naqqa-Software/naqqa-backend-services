package com.naqqa.auth.sso.admin;

import com.naqqa.auth.sso.support.SsoApiException;
import com.naqqa.sso.SsoException;
import com.naqqa.sso.oauth.SafeHttp;
import com.naqqa.sso.oidc.RedirectUris;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SsoAdminValidation {

    public static final int MAX_URIS = 20;
    public static final int MAX_URL_LENGTH = 2048;
    static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private SsoAdminValidation() {
    }

    private static URI parse(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_URL_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == '*' || c == '\\') {
                return null;
            }
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || value.indexOf('#') >= 0) {
                return null;
            }
            if (uri.getPort() == 0 || uri.getPort() > 65535) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean secureOrLoopback(URI uri, String raw) {
        String scheme = uri.getScheme();
        if (!raw.startsWith(scheme + "://") || !scheme.equals(scheme.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (!uri.getHost().equals(uri.getHost().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return scheme.equals("https") || scheme.equals("http") && LOOPBACK.contains(uri.getHost());
    }

    public static String redirectUri(String value, String code) {
        URI uri = parse(value);
        if (uri == null || !secureOrLoopback(uri, value)) {
            throw SsoApiException.badRequest(code, "URI must be an exact https URL (http only for localhost)");
        }
        try {
            RedirectUris.validateRegistered(value);
        } catch (SsoException e) {
            throw SsoApiException.badRequest(code, "URI must be an exact https URL (http only for localhost)");
        }
        return value;
    }

    public static List<String> redirectUris(List<String> values, String code) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_URIS) {
            throw SsoApiException.badRequest(code, "Too many URIs");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            out.add(redirectUri(v, code));
        }
        return new ArrayList<>(out);
    }

    public static boolean isOrigin(String value) {
        URI uri = parse(value);
        if (uri == null || !secureOrLoopback(uri, value)) {
            return false;
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isEmpty() || uri.getRawQuery() != null) {
            return false;
        }
        String canonical = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        return canonical.equals(value);
    }

    public static List<String> origins(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_URIS) {
            throw SsoApiException.badRequest("ORIGIN_INVALID", "Too many origins");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (!isOrigin(v)) {
                throw SsoApiException.badRequest("ORIGIN_INVALID", "Origin must be scheme://host[:port], https or http on localhost");
            }
            out.add(v);
        }
        return new ArrayList<>(out);
    }

    public static String logoUrl(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        URI uri = parse(value);
        if (uri == null || !"https".equals(uri.getScheme()) || !value.startsWith("https://")
                || !uri.getHost().equals(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw SsoApiException.badRequest("LOGO_URL_INVALID", "Logo URL must be an https URL");
        }
        return value;
    }

    public static String backchannelUri(String value, SafeHttp http) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        URI uri = parse(value);
        boolean loopbackTest = http.testMode() && uri != null && SafeHttp.LOOPBACK_TEST_HOST.equals(uri.getHost());
        if (uri == null || !(value.startsWith("https://") || loopbackTest && value.startsWith("http://"))) {
            throw SsoApiException.badRequest("BACKCHANNEL_URI_INVALID", "Back-channel logout URI must be an https URL");
        }
        try {
            RedirectUris.validateHttps(value);
            http.checkUrl(value);
        } catch (SsoException e) {
            if ("oauth.ssrf_blocked".equals(e.code())) {
                throw SsoApiException.badRequest("BACKCHANNEL_URI_BLOCKED", "Back-channel logout URI points to a blocked address");
            }
            if ("oauth.dns_failed".equals(e.code())) {
                throw SsoApiException.badRequest("BACKCHANNEL_URI_UNRESOLVABLE", "Back-channel logout host does not resolve");
            }
            throw SsoApiException.badRequest("BACKCHANNEL_URI_INVALID", "Back-channel logout URI must be an https URL");
        }
        return value;
    }

    public static String text(String value, int max, String code) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty() || v.length() > max) {
            throw SsoApiException.badRequest(code, "Value is empty or too long");
        }
        for (int i = 0; i < v.length(); i++) {
            if (Character.isISOControl(v.charAt(i))) {
                throw SsoApiException.badRequest(code, "Value contains control characters");
            }
        }
        return v;
    }
}
