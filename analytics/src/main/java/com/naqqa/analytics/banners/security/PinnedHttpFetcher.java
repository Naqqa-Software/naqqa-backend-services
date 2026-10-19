package com.naqqa.analytics.banners.security;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * SSRF-safe GET for server-side fetches of admin-supplied URLs. The host is resolved ONCE, every resolved
 * address must be public, and the TCP connection goes to that already-validated address (no second DNS lookup,
 * so no DNS-rebinding window). TLS keeps the original host for SNI and certificate verification, the request
 * carries the original Host header, redirects are never followed, and every failure surfaces as the same
 * generic {@link FetchException} (no reachability or port-scan oracle).
 */
public final class PinnedHttpFetcher {

    /** Generic failure; the message never says why (closed port, private address, TLS, status, ...). */
    public static final class FetchException extends IOException {
        public FetchException() {
            super("Remote file could not be fetched");
        }
    }

    private static final int MAX_HEADER_BYTES = 32 * 1024;

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public PinnedHttpFetcher(int connectTimeoutMs, int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** GETs the URL and returns at most {@code maxBytes + 1} body bytes (callers reject the extra byte). */
    public byte[] get(String url, long maxBytes) throws FetchException {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!("https".equals(scheme) || "http".equals(scheme)) || host == null || uri.getRawUserInfo() != null) {
                throw new FetchException();
            }
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
            InetAddress address = resolvePublic(host);
            String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            try (Socket socket = open(address, port, host, "https".equals(scheme))) {
                socket.setSoTimeout(readTimeoutMs);
                OutputStream out = socket.getOutputStream();
                String hostHeader = host + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
                out.write(("GET " + path + " HTTP/1.1\r\nHost: " + hostHeader
                        + "\r\nUser-Agent: naqqa-banner-import\r\nAccept: image/*\r\nAccept-Encoding: identity"
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return readResponse(new BufferedInputStream(socket.getInputStream()), maxBytes);
            }
        } catch (FetchException e) {
            throw e;
        } catch (Exception e) {
            throw new FetchException();
        }
    }

    static InetAddress resolvePublic(String host) throws FetchException {
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new FetchException();
        }
        if (all.length == 0) {
            throw new FetchException();
        }
        for (InetAddress address : all) {
            if (!isPublicAddress(address)) {
                throw new FetchException();
            }
        }
        return all[0];
    }

    private Socket open(InetAddress address, int port, String host, boolean tls) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(address, port), connectTimeoutMs);
        if (!tls) {
            return plain;
        }
        SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, port, true);
        SSLParameters params = ssl.getSSLParameters();
        params.setServerNames(List.of(new SNIHostName(host)));
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
        ssl.startHandshake();
        return ssl;
    }

    private static byte[] readResponse(InputStream in, long maxBytes) throws IOException {
        String head = readHead(in);
        String[] lines = head.split("\r\n");
        String[] status = lines[0].split(" ");
        if (status.length < 2 || !status[0].startsWith("HTTP/1.") || !"200".equals(status[1])) {
            throw new FetchException(); // includes every 3xx: redirects are never followed
        }
        long length = -1;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if ("content-length".equals(name)) {
                length = Long.parseLong(value);
            } else if ("transfer-encoding".equals(name) && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            }
        }
        long cap = maxBytes + 1;
        if (length > cap) {
            return new byte[(int) Math.min(cap, Integer.MAX_VALUE - 8)];
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (chunked) {
            while (body.size() <= maxBytes) {
                String sizeLine = readLine(in);
                int semi = sizeLine.indexOf(';');
                int size = Integer.parseInt((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
                if (size == 0) {
                    break;
                }
                body.write(in.readNBytes((int) Math.min(size, cap - body.size() + 1)));
                readLine(in);
            }
        } else {
            long toRead = length >= 0 ? length : cap;
            body.write(in.readNBytes((int) Math.min(toRead, cap)));
        }
        byte[] bytes = body.toByteArray();
        return bytes.length > cap ? Arrays.copyOf(bytes, (int) cap) : bytes;
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (head.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) {
                throw new FetchException();
            }
            head.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) {
                return head.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new FetchException();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            if (b != '\r') {
                line.write(b);
            }
            if (line.size() > 1024) {
                throw new FetchException();
            }
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    /** No loopback, private, link-local (incl. cloud metadata 169.254.169.254), CGNAT, multicast or reserved. */
    public static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            return !(b0 == 0 || (b0 == 100 && b1 >= 64 && b1 <= 127) || (b0 == 192 && b1 == 0 && (b[2] & 0xFF) == 0)
                    || (b0 == 198 && (b1 == 18 || b1 == 19)) || b0 >= 240);
        }
        if ((b[0] & 0xFE) == 0xFC) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return true;
            }
        }
        if ((b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            try {
                return isPublicAddress(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }
}
