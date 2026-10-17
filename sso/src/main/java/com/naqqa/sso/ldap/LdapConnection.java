package com.naqqa.sso.ldap;

import com.naqqa.sso.SsoException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LdapConnection implements AutoCloseable {
    static final String START_TLS_OID = "1.3.6.1.4.1.1466.20037";
    static final int APP_BIND_REQUEST = 0x60;
    static final int APP_BIND_RESPONSE = 0x61;
    static final int APP_UNBIND_REQUEST = 0x42;
    static final int APP_SEARCH_REQUEST = 0x63;
    static final int APP_SEARCH_ENTRY = 0x64;
    static final int APP_SEARCH_DONE = 0x65;
    static final int APP_SEARCH_REFERENCE = 0x73;
    static final int APP_EXTENDED_REQUEST = 0x77;
    static final int APP_EXTENDED_RESPONSE = 0x78;
    static final int MAX_ENTRIES = 16;
    static final int MAX_ATTRIBUTES = 256;
    static final int MAX_VALUES = 4096;
    private static final long TOTAL_RESPONSE_BYTES_FACTOR = 4;

    record Result(int code) {
    }

    record Entry(String dn, Map<String, List<String>> attributes) {
    }

    record SearchOutcome(List<Entry> entries, int code) {
    }

    private final LdapConfig config;
    private final long deadline;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int nextId = 1;
    private long received;
    private boolean closed;

    private LdapConnection(LdapConfig config) {
        this.config = config;
        this.deadline = System.nanoTime() + config.operationTimeout().toNanos();
    }

    static LdapConnection open(LdapConfig config) {
        LdapConnection c = new LdapConnection(config);
        try {
            c.connect();
            if (config.tls() == LdapConfig.TlsMode.START_TLS) {
                c.startTls();
            }
        } catch (RuntimeException e) {
            c.close();
            throw e;
        }
        return c;
    }

    private void connect() {
        Socket raw = new Socket();
        try {
            int timeout = (int) Math.min(config.connectTimeout().toMillis(), remainingMillis());
            raw.connect(new InetSocketAddress(config.host(), config.port()), timeout);
            raw.setTcpNoDelay(true);
            socket = raw;
            if (config.tls() == LdapConfig.TlsMode.LDAPS) {
                upgrade();
            }
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (SocketTimeoutException e) {
            closeQuietly(raw);
            throw new SsoException("ldap.timeout");
        } catch (SSLException e) {
            closeQuietly(raw);
            throw new SsoException("ldap.tls_failed");
        } catch (IOException e) {
            closeQuietly(raw);
            throw new SsoException("ldap.connect_failed");
        }
    }

    private void upgrade() throws IOException {
        SSLSocketFactory factory = config.sslSocketFactory();
        if (factory == null) {
            try {
                factory = javax.net.ssl.SSLContext.getDefault().getSocketFactory();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new SSLException("no tls");
            }
        }
        SSLSocket ssl = (SSLSocket) factory.createSocket(socket, config.host(), config.port(), true);
        SSLParameters p = ssl.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("LDAPS");
        List<String> protocols = new ArrayList<>();
        Set<String> supported = Set.of(ssl.getSupportedProtocols());
        for (String proto : new String[] {"TLSv1.3", "TLSv1.2"}) {
            if (supported.contains(proto)) {
                protocols.add(proto);
            }
        }
        if (protocols.isEmpty()) {
            throw new SSLException("no tls");
        }
        p.setProtocols(protocols.toArray(new String[0]));
        ssl.setSSLParameters(p);
        ssl.setSoTimeout((int) remainingMillis());
        ssl.startHandshake();
        socket = ssl;
    }

    private void startTls() {
        int id = send(LdapMessages.startTlsRequest());
        try {
            Message m = read(id, APP_EXTENDED_RESPONSE);
            Result r = LdapMessages.parseResult(m.body());
            if (r.code() != 0) {
                throw new SsoException("ldap.starttls_failed");
            }
            upgrade();
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (SocketTimeoutException e) {
            throw new SsoException("ldap.timeout");
        } catch (SSLException e) {
            throw new SsoException("ldap.tls_failed");
        } catch (IOException e) {
            throw new SsoException("ldap.io_error");
        }
    }

    record Message(int id, int op, BerReader body) {
    }

    int send(byte[] protocolOp) {
        int id = nextId++;
        byte[] frame = Ber.tlv(Ber.SEQUENCE, Ber.integer(id), protocolOp);
        try {
            socket.setSoTimeout((int) remainingMillis());
            out.write(frame);
            out.flush();
        } catch (SocketTimeoutException e) {
            throw new SsoException("ldap.timeout");
        } catch (IOException e) {
            throw new SsoException("ldap.io_error");
        }
        return id;
    }

    Message read(int expectedId, int expectedOp) {
        Message m = readAny(expectedId);
        if (m.op() != expectedOp) {
            throw BerReader.protocol();
        }
        return m;
    }

    Message readAny(int expectedId) {
        byte[] content;
        try {
            content = readFrame();
        } catch (SocketTimeoutException e) {
            throw new SsoException("ldap.timeout");
        } catch (IOException e) {
            throw new SsoException("ldap.io_error");
        }
        BerReader r = new BerReader(content);
        long id = r.integer(Ber.INTEGER);
        if (id != expectedId) {
            throw BerReader.protocol();
        }
        int op = r.peekTag();
        BerReader body = r.enter(op);
        if (r.hasMore()) {
            r.enter(0xa0);
        }
        r.finish();
        return new Message((int) id, op, body);
    }

    private byte[] readFrame() throws IOException {
        int tag = readByte();
        if (tag != Ber.SEQUENCE) {
            throw BerReader.protocol();
        }
        int first = readByte();
        long len;
        if (first < 0x80) {
            len = first;
        } else {
            int n = first & 0x7f;
            if (first == 0x80 || n > 4) {
                throw BerReader.protocol();
            }
            len = 0;
            for (int i = 0; i < n; i++) {
                int b = readByte();
                if (i == 0 && b == 0) {
                    throw BerReader.protocol();
                }
                len = (len << 8) | b;
            }
            if (len < 0x80) {
                throw BerReader.protocol();
            }
        }
        if (len > config.maxMessageBytes()) {
            throw new SsoException("ldap.response_too_large");
        }
        received += len;
        if (received > (long) config.maxMessageBytes() * TOTAL_RESPONSE_BYTES_FACTOR) {
            throw new SsoException("ldap.response_too_large");
        }
        byte[] buf = new byte[(int) len];
        int got = 0;
        while (got < buf.length) {
            socket.setSoTimeout((int) remainingMillis());
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) {
                throw new IOException();
            }
            got += n;
        }
        return buf;
    }

    private int readByte() throws IOException {
        socket.setSoTimeout((int) remainingMillis());
        int b = in.read();
        if (b < 0) {
            throw new IOException();
        }
        return b;
    }

    private long remainingMillis() {
        long ms = (deadline - System.nanoTime()) / 1_000_000L;
        if (ms <= 0) {
            throw new SsoException("ldap.timeout");
        }
        return Math.min(ms, Integer.MAX_VALUE);
    }

    Result bind(String name, char[] password) {
        int id = send(LdapMessages.bindRequest(name, password));
        Message m = read(id, APP_BIND_RESPONSE);
        return LdapMessages.parseResult(m.body());
    }

    SearchOutcome search(String baseDn, Filter filter, List<String> attributes, int sizeLimit, int timeLimit) {
        int id = send(LdapMessages.searchRequest(baseDn, filter, attributes, sizeLimit, timeLimit));
        List<Entry> entries = new ArrayList<>();
        while (true) {
            Message m = readAny(id);
            if (m.op() == APP_SEARCH_ENTRY) {
                if (entries.size() >= MAX_ENTRIES) {
                    throw new SsoException("ldap.response_too_large");
                }
                entries.add(LdapMessages.parseEntry(m.body()));
            } else if (m.op() == APP_SEARCH_REFERENCE) {
                continue;
            } else if (m.op() == APP_SEARCH_DONE) {
                return new SearchOutcome(entries, LdapMessages.parseResult(m.body()).code());
            } else {
                throw BerReader.protocol();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (socket != null && out != null) {
            try {
                socket.setSoTimeout(500);
                out.write(Ber.tlv(Ber.SEQUENCE, Ber.integer(nextId++), Ber.tlv(APP_UNBIND_REQUEST)));
                out.flush();
            } catch (IOException | RuntimeException ignored) {
                closeQuietly(socket);
            }
        }
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                return;
            }
        }
    }
}
