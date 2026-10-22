package com.naqqa.sso.oauth;

import com.naqqa.sso.SsoException;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class PinnedHttpClient {
    public static final int MAX_HEADERS = 100;
    public static final int MAX_LINE = 8 * 1024;
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Set<String> RESERVED = Set.of("host", "content-length", "connection", "transfer-encoding");

    @FunctionalInterface
    public interface Connector {
        Socket connect(InetAddress address, int port, int timeoutMillis) throws IOException;
    }

    public record Result(int status, Map<String, String> headers, byte[] body) {
    }

    private final Duration connectTimeout;
    private final Duration totalTimeout;
    private final int maxBytes;
    private final SafeHttp.Resolver resolver;
    private final SSLSocketFactory tls;
    private final Connector connector;

    public PinnedHttpClient(Duration connectTimeout, Duration totalTimeout, int maxBytes, SafeHttp.Resolver resolver,
                            SSLSocketFactory tls, Connector connector) {
        if (connectTimeout == null || totalTimeout == null || maxBytes < 1 || resolver == null) {
            throw new SsoException("oauth.http_options_invalid");
        }
        this.connectTimeout = connectTimeout;
        this.totalTimeout = totalTimeout;
        this.maxBytes = maxBytes;
        this.resolver = resolver;
        this.tls = tls;
        this.connector = connector == null ? PinnedHttpClient::plainConnect : connector;
    }

    public PinnedHttpClient(Duration connectTimeout, Duration totalTimeout, int maxBytes, SafeHttp.Resolver resolver) {
        this(connectTimeout, totalTimeout, maxBytes, resolver, null, null);
    }

    private static Socket plainConnect(InetAddress address, int port, int timeoutMillis) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(address, port), timeoutMillis);
        } catch (IOException e) {
            s.close();
            throw e;
        }
        return s;
    }

    public Result execute(String method, URI uri, Map<String, String> headers, byte[] body, boolean plainLoopback) {
        long deadline = System.nanoTime() + totalTimeout.toNanos();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean secure = scheme.equals("https");
        if (!secure && !(plainLoopback && scheme.equals("http"))) {
            throw new SsoException("oauth.url_insecure");
        }
        String rawHost = uri.getHost();
        if (rawHost == null) {
            throw new SsoException("oauth.url_invalid");
        }
        String host = rawHost.startsWith("[") && rawHost.endsWith("]") ? rawHost.substring(1, rawHost.length() - 1) : rawHost;
        int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
        List<InetAddress> candidates = resolveChecked(host, plainLoopback);
        byte[] request = buildRequest(method, uri, rawHost, port, secure, headers, body);
        Socket socket = null;
        try {
            socket = connectFirst(candidates, port, deadline);
            if (secure) {
                socket = handshake(socket, host, port, deadline);
            }
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            return readResponse(socket, deadline);
        } catch (SocketTimeoutException e) {
            throw new SsoException("oauth.timeout");
        } catch (SSLException e) {
            throw new SsoException("oauth.tls_failed");
        } catch (IOException e) {
            throw new SsoException("oauth.io_error");
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    throw new SsoException("oauth.io_error");
                }
            }
        }
    }

    private List<InetAddress> resolveChecked(String host, boolean plainLoopback) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException | RuntimeException e) {
            throw new SsoException("oauth.dns_failed");
        }
        if (addresses == null || addresses.length == 0) {
            throw new SsoException("oauth.dns_failed");
        }
        List<InetAddress> out = new ArrayList<>();
        for (InetAddress a : addresses) {
            if (!plainLoopback && IpGuard.isBlocked(a)) {
                throw new SsoException("oauth.ssrf_blocked");
            }
            out.add(a);
        }
        return out;
    }

    private Socket connectFirst(List<InetAddress> candidates, int port, long deadline) throws IOException {
        IOException last = null;
        for (InetAddress a : candidates) {
            long remaining = remainingMillis(deadline);
            int timeout = (int) Math.min(connectTimeout.toMillis(), remaining);
            try {
                Socket s = connector.connect(a, port, timeout);
                s.setSoTimeout((int) Math.max(1, remainingMillis(deadline)));
                s.setTcpNoDelay(true);
                return s;
            } catch (SocketTimeoutException e) {
                last = e;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException() : last;
    }

    private Socket handshake(Socket raw, String host, int port, long deadline) throws IOException {
        SSLSocketFactory factory = tls;
        if (factory == null) {
            try {
                factory = javax.net.ssl.SSLContext.getDefault().getSocketFactory();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new SsoException("oauth.tls_failed");
            }
        }
        SSLSocket ssl = (SSLSocket) factory.createSocket(raw, host, port, true);
        SSLParameters p = ssl.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        List<String> protocols = new ArrayList<>();
        Set<String> supported = Set.of(ssl.getSupportedProtocols());
        for (String proto : new String[] {"TLSv1.3", "TLSv1.2"}) {
            if (supported.contains(proto)) {
                protocols.add(proto);
            }
        }
        if (protocols.isEmpty()) {
            throw new SsoException("oauth.tls_failed");
        }
        p.setProtocols(protocols.toArray(new String[0]));
        if (!isIpLiteral(host)) {
            try {
                p.setServerNames(List.of(new SNIHostName(host)));
            } catch (IllegalArgumentException e) {
                throw new SsoException("oauth.url_invalid");
            }
        }
        ssl.setSSLParameters(p);
        ssl.setSoTimeout((int) Math.max(1, remainingMillis(deadline)));
        ssl.startHandshake();
        return ssl;
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        String last = host.substring(host.lastIndexOf('.') + 1);
        return !last.isEmpty() && Character.isDigit(last.charAt(0));
    }

    private long remainingMillis(long deadline) {
        long ms = (deadline - System.nanoTime()) / 1_000_000L;
        if (ms <= 0) {
            throw new SsoException("oauth.timeout");
        }
        return ms;
    }

    private static byte[] buildRequest(String method, URI uri, String rawHost, int port, boolean secure,
                                       Map<String, String> headers, byte[] body) {
        if (!method.equals("GET") && !method.equals("POST")) {
            throw new SsoException("oauth.http_options_invalid");
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        checkValue(path);
        boolean defaultPort = secure ? port == 443 : port == 80;
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(rawHost).append(defaultPort || uri.getPort() <= 0 ? "" : ":" + port).append("\r\n");
        sb.append("Connection: close\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String name = e.getKey();
                String value = e.getValue();
                if (name == null || value == null || !TOKEN.matcher(name).matches()) {
                    throw new SsoException("oauth.http_header_invalid");
                }
                checkValue(value);
                if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                sb.append(name).append(": ").append(value).append("\r\n");
            }
        }
        byte[] payload = body == null ? new byte[0] : body;
        if (method.equals("POST") || payload.length > 0) {
            sb.append("Content-Length: ").append(payload.length).append("\r\n");
        }
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[head.length + payload.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        return out;
    }

    private static void checkValue(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7f || c > 0xff) {
                throw new SsoException("oauth.http_header_invalid");
            }
        }
    }

    private Result readResponse(Socket socket, long deadline) throws IOException {
        InputStream in = socket.getInputStream();
        Reader r = new Reader(in, socket, deadline);
        String statusLine = r.line();
        if (!statusLine.startsWith("HTTP/1.1 ") && !statusLine.startsWith("HTTP/1.0 ")) {
            throw malformed();
        }
        if (statusLine.length() < 12) {
            throw malformed();
        }
        String code = statusLine.substring(9, 12);
        if (!code.chars().allMatch(c -> c >= '0' && c <= '9') || (statusLine.length() > 12 && statusLine.charAt(12) != ' ')) {
            throw malformed();
        }
        int status = Integer.parseInt(code);
        if (status < 200 || status > 599) {
            throw malformed();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        String contentLength = null;
        String transferEncoding = null;
        int count = 0;
        while (true) {
            String line = r.line();
            if (line.isEmpty()) {
                break;
            }
            if (++count > MAX_HEADERS) {
                throw malformed();
            }
            char first = line.charAt(0);
            if (first == ' ' || first == '\t') {
                throw malformed();
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw malformed();
            }
            String name = line.substring(0, colon);
            if (!TOKEN.matcher(name).matches()) {
                throw malformed();
            }
            String value = trimOws(line.substring(colon + 1));
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 && c != '\t' || c == 0x7f) {
                    throw malformed();
                }
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (key.equals("content-length")) {
                if (contentLength != null) {
                    throw malformed();
                }
                contentLength = value;
            } else if (key.equals("transfer-encoding")) {
                if (transferEncoding != null) {
                    throw malformed();
                }
                transferEncoding = value;
            }
            headers.merge(key, value, (a, b) -> a + ", " + b);
        }
        byte[] body;
        boolean noBody = status == 204 || status == 304;
        if (transferEncoding != null) {
            if (contentLength != null || !transferEncoding.equalsIgnoreCase("chunked")) {
                throw malformed();
            }
            body = noBody ? new byte[0] : r.chunked();
        } else if (contentLength != null) {
            if (contentLength.isEmpty() || contentLength.length() > 10 || !contentLength.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw malformed();
            }
            long n = Long.parseLong(contentLength);
            if (n > maxBytes) {
                throw new SsoException("oauth.response_too_large");
            }
            body = noBody ? new byte[0] : r.fixed((int) n);
        } else {
            body = noBody ? new byte[0] : r.untilClose();
        }
        return new Result(status, headers, body);
    }

    private static String trimOws(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) {
            a++;
        }
        while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) {
            b--;
        }
        return s.substring(a, b);
    }

    private static SsoException malformed() {
        return new SsoException("oauth.http_malformed");
    }

    private final class Reader {
        private final InputStream in;
        private final Socket socket;
        private final long deadline;
        private final byte[] buf = new byte[4096];
        private int pos;
        private int lim;

        Reader(InputStream in, Socket socket, long deadline) {
            this.in = in;
            this.socket = socket;
            this.deadline = deadline;
        }

        private boolean fill() throws IOException {
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis(deadline)));
            int n;
            try {
                n = in.read(buf, 0, buf.length);
            } catch (SSLException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("close_notify")) {
                    return false;
                }
                throw e;
            }
            if (n < 0) {
                return false;
            }
            pos = 0;
            lim = n;
            return true;
        }

        private int next() throws IOException {
            if (pos >= lim && !fill()) {
                return -1;
            }
            return buf[pos++] & 0xff;
        }

        String line() throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            while (true) {
                int b = next();
                if (b < 0) {
                    throw malformed();
                }
                if (b == '\n') {
                    throw malformed();
                }
                if (b == '\r') {
                    if (next() != '\n') {
                        throw malformed();
                    }
                    return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
                }
                if (bo.size() >= MAX_LINE) {
                    throw malformed();
                }
                bo.write(b);
            }
        }

        byte[] fixed(int n) throws IOException {
            byte[] out = new byte[n];
            readFully(out, 0, n);
            return out;
        }

        private void readFully(byte[] out, int off, int n) throws IOException {
            int got = 0;
            while (got < n) {
                if (pos >= lim && !fill()) {
                    throw new SsoException("oauth.response_truncated");
                }
                int c = Math.min(n - got, lim - pos);
                System.arraycopy(buf, pos, out, off + got, c);
                pos += c;
                got += c;
            }
        }

        byte[] untilClose() throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            while (true) {
                if (pos >= lim && !fill()) {
                    return bo.toByteArray();
                }
                int c = lim - pos;
                if (bo.size() + c > maxBytes) {
                    throw new SsoException("oauth.response_too_large");
                }
                bo.write(buf, pos, c);
                pos = lim;
            }
        }

        byte[] chunked() throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = line();
                if (sizeLine.isEmpty() || sizeLine.length() > 8 || !sizeLine.chars().allMatch(c -> Character.digit(c, 16) >= 0 && c < 128)) {
                    throw malformed();
                }
                long size = Long.parseLong(sizeLine, 16);
                if (size == 0) {
                    int trailers = 0;
                    while (!line().isEmpty()) {
                        if (++trailers > MAX_HEADERS) {
                            throw malformed();
                        }
                    }
                    return bo.toByteArray();
                }
                if (bo.size() + size > maxBytes) {
                    throw new SsoException("oauth.response_too_large");
                }
                byte[] chunk = new byte[(int) size];
                readFully(chunk, 0, chunk.length);
                bo.write(chunk);
                if (next() != '\r' || next() != '\n') {
                    throw malformed();
                }
            }
        }
    }
}
