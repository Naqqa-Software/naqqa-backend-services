package com.naqqa.seofarm.service;

import org.jsoup.Connection;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * SSRF guard for the SEO crawlers (competitor pages from search results). Result URLs, and the pages they
 * redirect to, are third-party controlled, so before every request, redirects included, the URL must be
 * http(s) and its host must resolve only to public unicast addresses.
 */
public final class PublicUrlGuard {

    /** Most redirects followed for one fetch, each hop checked. */
    static final int MAX_REDIRECTS = 5;

    public static final class UnsafeUrlException extends IOException {
        public UnsafeUrlException(String message) {
            super(message);
        }
    }

    private PublicUrlGuard() {
    }

    public static boolean isPublic(String url) {
        try {
            requirePublic(url);
            return true;
        } catch (UnsafeUrlException e) {
            return false;
        }
    }

    /** Returns the URL when it is safe to open, otherwise throws {@link UnsafeUrlException}. */
    public static String requirePublic(String url) throws UnsafeUrlException {
        if (url == null || url.isBlank()) {
            throw new UnsafeUrlException("URL is required");
        }
        URL parsed;
        try {
            parsed = new URL(url.trim());
        } catch (MalformedURLException e) {
            throw new UnsafeUrlException("URL is not valid");
        }
        String scheme = parsed.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new UnsafeUrlException("URL must be http or https");
        }
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new UnsafeUrlException("URL must have a host");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("URL host could not be resolved");
        }
        if (addresses.length == 0) {
            throw new UnsafeUrlException("URL host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new UnsafeUrlException("URL must point to a public internet address");
            }
        }
        return url;
    }

    /**
     * Executes a GET with redirects followed by hand, every hop checked by {@link #requirePublic}. {@code setup}
     * configures the connection (user agent, timeouts, proxy, error handling); redirects are always disabled on it.
     */
    public static Connection.Response fetch(String url, UnaryOperator<Connection> setup) throws IOException {
        String next = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requirePublic(next);
            Connection.Response res = setup.apply(Jsoup.connect(next)).followRedirects(false).execute();
            String location = res.header("Location");
            if (res.statusCode() < 300 || res.statusCode() >= 400 || location == null || location.isBlank()) {
                return res;
            }
            next = new URL(res.url(), location).toString();
        }
        throw new UnsafeUrlException("Too many redirects");
    }

    /** True when the address is not a public unicast address. */
    static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isBlockedV4(b);
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) {
                return true; // unique local fc00::/7
            }
            if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) != 0) {
                return true; // site-local fec0::/10, link-local fe80::/10
            }
            boolean firstTenZero = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    firstTenZero = false;
                    break;
                }
            }
            if (firstTenZero && (((b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) || (b[10] == 0 && b[11] == 0))) {
                return isBlockedV4(new byte[] { b[12], b[13], b[14], b[15] }); // IPv4-mapped or compatible
            }
            return false;
        }
        return true;
    }

    private static boolean isBlockedV4(byte[] b) {
        int a0 = b[0] & 0xFF;
        int a1 = b[1] & 0xFF;
        int a2 = b[2] & 0xFF;
        return a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224
                || (a0 == 100 && a1 >= 64 && a1 <= 127)   // carrier-grade NAT 100.64.0.0/10
                || (a0 == 169 && a1 == 254)
                || (a0 == 172 && a1 >= 16 && a1 <= 31)
                || (a0 == 192 && a1 == 168)
                || (a0 == 192 && a1 == 0 && a2 == 0)       // 192.0.0.0/24
                || (a0 == 198 && (a1 == 18 || a1 == 19));  // benchmarking 198.18.0.0/15
    }
}
