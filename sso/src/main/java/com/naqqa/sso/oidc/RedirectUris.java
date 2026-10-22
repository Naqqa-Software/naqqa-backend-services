package com.naqqa.sso.oidc;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.oauth.IpGuard;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RedirectUris {
    public static final int MAX_LENGTH = 2048;
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private RedirectUris() {
    }

    public static String validateRegistered(String uri) {
        if (uri == null || uri.isEmpty() || uri.length() > MAX_LENGTH) {
            throw new SsoException("oidc.redirect_uri_invalid");
        }
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == '*' || c == '\\') {
                throw new SsoException("oidc.redirect_uri_invalid");
            }
        }
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException e) {
            throw new SsoException("oidc.redirect_uri_invalid");
        }
        if (!parsed.isAbsolute() || parsed.getRawFragment() != null || uri.indexOf('#') >= 0) {
            throw new SsoException("oidc.redirect_uri_invalid");
        }
        String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals(parsed.getScheme())) {
            throw new SsoException("oidc.redirect_uri_invalid");
        }
        if (scheme.equals("https") || scheme.equals("http")) {
            if (parsed.getRawUserInfo() != null || parsed.getHost() == null || parsed.isOpaque()) {
                throw new SsoException("oidc.redirect_uri_invalid");
            }
            if (!parsed.getHost().equals(parsed.getHost().toLowerCase(Locale.ROOT))) {
                throw new SsoException("oidc.redirect_uri_invalid");
            }
            if (scheme.equals("http") && !LOOPBACK.contains(parsed.getHost())) {
                throw new SsoException("oidc.redirect_uri_insecure");
            }
            return uri;
        }
        if (scheme.equals("javascript") || scheme.equals("data") || scheme.equals("file") || scheme.equals("vbscript")) {
            throw new SsoException("oidc.redirect_uri_invalid");
        }
        if (scheme.indexOf('.') < 0 || parsed.getRawUserInfo() != null) {
            throw new SsoException("oidc.redirect_uri_invalid");
        }
        return uri;
    }

    public static boolean matches(List<String> registered, String presented) {
        if (presented == null || registered == null) {
            return false;
        }
        for (String r : registered) {
            if (r.equals(presented)) {
                return true;
            }
        }
        return false;
    }

    public static String validateHttps(String uri) {
        String v = validateRegistered(uri);
        String scheme = URI.create(v).getScheme();
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new SsoException("oidc.uri_scheme_invalid");
        }
        return v;
    }

    public static String validateBackchannel(String uri, boolean allowLoopback) {
        String v;
        try {
            v = validateHttps(uri);
        } catch (SsoException e) {
            throw new SsoException("oidc.backchannel_uri_invalid");
        }
        URI parsed = URI.create(v);
        String host = parsed.getHost();
        String bare = (host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host).toLowerCase(Locale.ROOT);
        if (bare.endsWith(".")) {
            bare = bare.substring(0, bare.length() - 1);
        }
        InetAddress literal = literal(bare);
        boolean loopback = bare.equals("localhost") || bare.endsWith(".localhost") || (literal != null && loopback(literal));
        if (loopback) {
            if (!allowLoopback) {
                throw new SsoException("oidc.backchannel_uri_blocked");
            }
            return v;
        }
        if (literal != null && IpGuard.isBlocked(literal)) {
            throw new SsoException("oidc.backchannel_uri_blocked");
        }
        if (!parsed.getScheme().equals("https")) {
            throw new SsoException("oidc.backchannel_uri_invalid");
        }
        return v;
    }

    private static InetAddress literal(String host) {
        if (host.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(host);
            } catch (UnknownHostException | RuntimeException e) {
                throw new SsoException("oidc.backchannel_uri_invalid");
            }
        }
        String last = host.substring(host.lastIndexOf('.') + 1);
        if (last.isEmpty() || !Character.isDigit(last.charAt(0))) {
            return null;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new SsoException("oidc.backchannel_uri_invalid");
        }
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0') || !p.chars().allMatch(c -> c >= '0' && c <= '9')
                    || Integer.parseInt(p) > 255) {
                throw new SsoException("oidc.backchannel_uri_invalid");
            }
            b[i] = (byte) Integer.parseInt(p);
        }
        try {
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            throw new SsoException("oidc.backchannel_uri_invalid");
        }
    }

    private static boolean loopback(InetAddress a) {
        if (a.isLoopbackAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF && (b[12] & 0xFF) == 127;
    }
}
