package com.naqqa.sso.ldap;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeLdapServer implements AutoCloseable {
    enum Mode {
        NORMAL, HUGE, INDEFINITE, NON_MINIMAL_LENGTH, REFERRAL, WRONG_ID, SILENT_SEARCH, TRICKLE_SEARCH, UNSOLICITED
    }

    record User(String dn, Map<String, List<String>> attrs, String password) {
    }

    record BindSeen(String name, int passwordLength, boolean tls) {
    }

    record SearchSeen(String filter, int sizeLimit, int timeLimit, String base) {
    }

    static final String SERVICE_DN = "cn=svc,dc=ex";
    static final String SERVICE_PW = "svc-pass";

    final ServerSocket server;
    final TestTls tls;
    final boolean implicitTls;
    final List<User> users = new CopyOnWriteArrayList<>();
    final List<BindSeen> binds = new CopyOnWriteArrayList<>();
    final List<SearchSeen> searches = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    final AtomicInteger unbinds = new AtomicInteger();
    volatile Mode mode = Mode.NORMAL;
    volatile boolean startTlsOk = true;
    volatile int adminSizeLimit;

    FakeLdapServer(TestTls tls, boolean implicitTls) throws IOException {
        this.tls = tls;
        this.implicitTls = implicitTls;
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        this.server = implicitTls
                ? tls.server.getServerSocketFactory().createServerSocket(0, 20, lo)
                : new ServerSocket(0, 20, lo);
        Thread t = new Thread(this::acceptLoop);
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    User add(String dn, String password, Map<String, List<String>> attrs) {
        User u = new User(dn, attrs, password);
        users.add(u);
        return u;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                connections.incrementAndGet();
                Thread t = new Thread(() -> handle(s));
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket initial) {
        Socket s = initial;
        try {
            s.setSoTimeout(10000);
            boolean tlsOn = implicitTls;
            while (true) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                int tag = in.read();
                if (tag < 0) {
                    return;
                }
                int len = in.read();
                if (len >= 0x80) {
                    int n = len & 0x7f;
                    len = 0;
                    for (int i = 0; i < n; i++) {
                        len = (len << 8) | in.read();
                    }
                }
                byte[] content = in.readNBytes(len);
                BerReader r = new BerReader(content);
                int id = (int) r.integer(Ber.INTEGER);
                int op = r.peekTag();
                BerReader body = r.enter(op);
                if (op == LdapConnection.APP_UNBIND_REQUEST) {
                    unbinds.incrementAndGet();
                    return;
                }
                if (op == LdapConnection.APP_EXTENDED_REQUEST) {
                    if (startTlsOk) {
                        write(out, id, result(LdapConnection.APP_EXTENDED_RESPONSE, 0));
                        SSLSocket ssl = (SSLSocket) tls.server.getSocketFactory().createSocket(s, null, s.getPort(), true);
                        ssl.setUseClientMode(false);
                        ssl.startHandshake();
                        s = ssl;
                        tlsOn = true;
                    } else {
                        write(out, id, result(LdapConnection.APP_EXTENDED_RESPONSE, 2));
                    }
                    continue;
                }
                if (op == LdapConnection.APP_BIND_REQUEST) {
                    body.integer(Ber.INTEGER);
                    String name = body.string(Ber.OCTET_STRING);
                    String pw = body.string(0x80);
                    binds.add(new BindSeen(name, pw.length(), tlsOn));
                    write(out, id, result(LdapConnection.APP_BIND_RESPONSE, bindCode(name, pw)));
                    continue;
                }
                if (op == LdapConnection.APP_SEARCH_REQUEST) {
                    search(out, id, body);
                    continue;
                }
                return;
            }
        } catch (Exception e) {
            return;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                connections.get();
            }
        }
    }

    private int bindCode(String name, String pw) {
        if (name.equals(SERVICE_DN)) {
            return pw.equals(SERVICE_PW) ? 0 : 49;
        }
        if (pw.isEmpty()) {
            return 0;
        }
        for (User u : users) {
            if ((u.dn().equals(name) || upn(u).equals(name)) && u.password().equals(pw)) {
                return 0;
            }
        }
        return 49;
    }

    private static String upn(User u) {
        List<String> v = u.attrs().get("userPrincipalName");
        return v == null ? "" : v.get(0);
    }

    private void search(OutputStream out, int id, BerReader body) throws Exception {
        String base = body.string(Ber.OCTET_STRING);
        body.integer(Ber.ENUMERATED);
        body.integer(Ber.ENUMERATED);
        int size = (int) body.integer(Ber.INTEGER);
        int time = (int) body.integer(Ber.INTEGER);
        body.octets(Ber.BOOLEAN);
        Filter f = decode(body);
        searches.add(new SearchSeen(f.toRfc4515(), size, time, base));
        switch (mode) {
            case HUGE -> {
                out.write(new byte[] {0x30, (byte) 0x84, 0x10, 0x00, 0x00, 0x00});
                out.flush();
                return;
            }
            case INDEFINITE -> {
                out.write(new byte[] {0x30, (byte) 0x80, 0x02, 0x01, 0x01, 0x00, 0x00});
                out.flush();
                return;
            }
            case NON_MINIMAL_LENGTH -> {
                out.write(new byte[] {0x30, (byte) 0x81, 0x05, 0x02, 0x01, 0x01, 0x65, 0x00});
                out.flush();
                return;
            }
            case SILENT_SEARCH -> {
                Thread.sleep(4000);
                return;
            }
            case TRICKLE_SEARCH -> {
                for (int i = 0; i < 100; i++) {
                    out.write(0x30);
                    out.write(0x81);
                    out.flush();
                    Thread.sleep(50);
                }
                return;
            }
            case UNSOLICITED -> {
                write(out, 0, result(LdapConnection.APP_SEARCH_DONE, 0));
                return;
            }
            case WRONG_ID -> {
                write(out, id + 7, result(LdapConnection.APP_SEARCH_DONE, 0));
                return;
            }
            default -> {
            }
        }
        if (mode == Mode.REFERRAL) {
            write(out, id, Ber.tlv(LdapConnection.APP_SEARCH_REFERENCE, Ber.octets("ldap://evil.example/dc=ex")));
        }
        int sent = 0;
        boolean exceeded = false;
        if (adminSizeLimit > 0 && (size <= 0 || adminSizeLimit < size)) {
            size = adminSizeLimit;
        }
        for (User u : users) {
            if (matches(f, u)) {
                if (size > 0 && sent >= size) {
                    exceeded = true;
                    break;
                }
                write(out, id, entry(u));
                sent++;
            }
        }
        if (mode == Mode.REFERRAL) {
            write(out, id, Ber.tlv(LdapConnection.APP_SEARCH_DONE, Ber.enumerated(10), Ber.octets(""), Ber.octets(""),
                    Ber.tlv(0xa3, Ber.octets("ldap://evil.example/dc=ex"))));
        } else {
            write(out, id, result(LdapConnection.APP_SEARCH_DONE, exceeded ? 4 : 0));
        }
    }

    private static Filter decode(BerReader r) {
        int tag = r.peekTag();
        if (tag == 0xa0 || tag == 0xa1) {
            BerReader c = r.enter(tag);
            List<Filter> parts = new ArrayList<>();
            while (c.hasMore()) {
                parts.add(decode(c));
            }
            return tag == 0xa0 ? Filter.and(parts) : Filter.or(parts);
        }
        if (tag == 0xa3) {
            BerReader c = r.enter(tag);
            String a = c.string(Ber.OCTET_STRING);
            String v = c.string(Ber.OCTET_STRING);
            c.finish();
            return Filter.equal(a, v);
        }
        if (tag == 0x87) {
            return Filter.present(r.string(0x87));
        }
        throw new IllegalStateException();
    }

    private static boolean matches(Filter f, User u) {
        if (f instanceof Filter.Equal e) {
            for (Map.Entry<String, List<String>> a : u.attrs().entrySet()) {
                if (a.getKey().equalsIgnoreCase(e.attribute())) {
                    for (String v : a.getValue()) {
                        if (v.equalsIgnoreCase(e.value())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        if (f instanceof Filter.Present p) {
            return u.attrs().keySet().stream().anyMatch(k -> k.equalsIgnoreCase(p.attribute()));
        }
        if (f instanceof Filter.And a) {
            return a.parts().stream().allMatch(x -> matches(x, u));
        }
        return ((Filter.Or) f).parts().stream().anyMatch(x -> matches(x, u));
    }

    private static byte[] entry(User u) {
        List<byte[]> attrs = new ArrayList<>();
        for (Map.Entry<String, List<String>> a : u.attrs().entrySet()) {
            byte[][] vals = a.getValue().stream().map(Ber::octets).toArray(byte[][]::new);
            attrs.add(Ber.tlv(Ber.SEQUENCE, Ber.octets(a.getKey()), Ber.tlv(Ber.SET, vals)));
        }
        return Ber.tlv(LdapConnection.APP_SEARCH_ENTRY, Ber.octets(u.dn()), Ber.tlv(Ber.SEQUENCE, attrs.toArray(new byte[0][])));
    }

    private static byte[] result(int op, int code) {
        return Ber.tlv(op, Ber.enumerated(code), Ber.octets(""), Ber.octets(""));
    }

    private static void write(OutputStream out, int id, byte[] op) throws IOException {
        out.write(Ber.tlv(Ber.SEQUENCE, Ber.integer(id), op));
        out.flush();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    static Map<String, List<String>> attrs(String... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.computeIfAbsent(kv[i], k -> new ArrayList<>()).add(kv[i + 1]);
        }
        return m;
    }
}
