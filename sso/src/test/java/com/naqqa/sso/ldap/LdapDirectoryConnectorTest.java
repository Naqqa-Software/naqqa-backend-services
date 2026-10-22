package com.naqqa.sso.ldap;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.oauth.DirectoryConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdapDirectoryConnectorTest {
    private static final String ALICE = "uid=alice,ou=people,dc=ex";
    private static TestTls goodTls;
    private static TestTls otherTls;

    @TempDir
    static Path tmp;

    private FakeLdapServer server;

    @BeforeAll
    static void keys() throws Exception {
        goodTls = TestTls.create(tmp, "good", "ip:127.0.0.1");
        otherTls = TestTls.create(tmp, "other", "dns:other.test");
    }

    @AfterEach
    void down() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    private void start(boolean implicitTls) throws Exception {
        server = new FakeLdapServer(goodTls, implicitTls);
        server.add(ALICE, "alice-pw", FakeLdapServer.attrs("uid", "alice", "mail", "alice@ex.test", "sAMAccountName", "alice",
                "userPrincipalName", "alice@ad.ex.test", "memberOf", "cn=staff,dc=ex", "memberOf", "cn=admins,dc=ex"));
        server.add("uid=bob,ou=people,dc=ex", "bob-pw", FakeLdapServer.attrs("uid", "bob", "mail", "bob@ex.test"));
    }

    private LdapConfig config(LdapConfig.TlsMode mode) {
        return configRaw(mode).withSslSocketFactory(goodTls.client.getSocketFactory());
    }

    private LdapConfig configRaw(LdapConfig.TlsMode mode) {
        return LdapConfig.of("127.0.0.1", server.port(), mode, "dc=ex", FakeLdapServer.SERVICE_DN,
                FakeLdapServer.SERVICE_PW.toCharArray());
    }

    private LdapDirectoryConnector ldaps() {
        return new LdapDirectoryConnector(config(LdapConfig.TlsMode.LDAPS));
    }

    private static String code(Runnable r) {
        return assertThrows(SsoException.class, r::run).code();
    }

    @Test
    @DisplayName("SSO-T-ldap-001 LDAPS service bind, search, user bind succeeds and returns DN and memberOf groups")
    void success() throws Exception {
        start(true);
        LdapDirectoryConnector c = ldaps();
        DirectoryConnector.DirectoryUser u = c.bind("alice", "alice-pw".toCharArray());
        assertEquals(ALICE, u.dn());
        assertEquals(List.of("cn=staff,dc=ex", "cn=admins,dc=ex"), c.groups(u));
        assertEquals(2, server.connections.get());
        assertEquals(FakeLdapServer.SERVICE_DN, server.binds.get(0).name());
        assertEquals(ALICE, server.binds.get(1).name());
        assertTrue(server.binds.stream().allMatch(FakeLdapServer.BindSeen::tls));
    }

    @Test
    @DisplayName("SSO-T-ldap-002 wrong password rejected with invalid_credentials")
    void wrongPassword() throws Exception {
        start(true);
        assertEquals("ldap.invalid_credentials", code(() -> ldaps().bind("alice", "nope".toCharArray())));
    }

    @Test
    @DisplayName("SSO-T-ldap-003 unknown user rejected with the same code as a wrong password")
    void unknownUser() throws Exception {
        start(true);
        assertEquals("ldap.invalid_credentials", code(() -> ldaps().bind("nobody", "x".toCharArray())));
    }

    @Test
    @DisplayName("SSO-T-ldap-004 empty password rejected before any network use (unauthenticated bind bypass)")
    void emptyPassword() throws Exception {
        start(true);
        assertEquals("ldap.password_empty", code(() -> ldaps().bind("alice", new char[0])));
        assertEquals("ldap.password_empty", code(() -> ldaps().bind("alice", null)));
        assertEquals(0, server.connections.get());
        assertTrue(server.binds.isEmpty());
    }

    @Test
    @DisplayName("SSO-T-ldap-005 filter injection attempt is matched as a literal value and finds nobody")
    void filterInjection() throws Exception {
        start(true);
        assertEquals("ldap.invalid_credentials", code(() -> ldaps().bind("*)(uid=*", "x".toCharArray())));
        FakeLdapServer.SearchSeen s = server.searches.get(0);
        assertEquals("(|(uid=\\2a\\29\\28uid=\\2a)(sAMAccountName=\\2a\\29\\28uid=\\2a)(mail=\\2a\\29\\28uid=\\2a))", s.filter());
    }

    @Test
    @DisplayName("SSO-T-ldap-006 wildcard username does not match every user")
    void wildcardUsername() throws Exception {
        start(true);
        assertEquals("ldap.invalid_credentials", code(() -> ldaps().bind("*", "alice-pw".toCharArray())));
        assertEquals("ldap.invalid_credentials", code(() -> ldaps().bind("a*", "alice-pw".toCharArray())));
    }

    @Test
    @DisplayName("SSO-T-ldap-007 RFC 4515 escaping covers * ( ) backslash NUL and non-ASCII")
    void escaping() {
        assertEquals("\\2a\\28\\29\\5c\\00\\c3\\a9x", Filter.escape("*()\\\u0000éx"));
        assertEquals("(&(uid=a\\2ab)(|(mail=x)(cn=*)))",
                Filter.and(List.of(Filter.equal("uid", "a*b"), Filter.or(List.of(Filter.equal("mail", "x"), Filter.present("cn"))))).toRfc4515());
        assertEquals("ldap.attribute_invalid", code(() -> Filter.equal("uid)(x", "a")));
    }

    @Test
    @DisplayName("SSO-T-ldap-008 two matching entries is ambiguous and rejected")
    void ambiguous() throws Exception {
        start(true);
        server.add("uid=alice2,ou=other,dc=ex", "pw", FakeLdapServer.attrs("uid", "alice", "mail", "alice@x.test"));
        assertEquals("ldap.ambiguous_user", code(() -> ldaps().bind("alice", "alice-pw".toCharArray())));
        assertTrue(server.binds.stream().noneMatch(b -> b.name().equals(ALICE)));
    }

    @Test
    @DisplayName("SSO-T-ldap-009 search asks for size limit 2 and a time limit with subtree scope under base DN")
    void limits() throws Exception {
        start(true);
        ldaps().bind("alice", "alice-pw".toCharArray());
        FakeLdapServer.SearchSeen s = server.searches.get(0);
        assertEquals(2, s.sizeLimit());
        assertEquals(5, s.timeLimit());
        assertEquals("dc=ex", s.base());
    }

    @Test
    @DisplayName("SSO-T-ldap-010 oversized message announced by the server is rejected without allocation")
    void oversized() throws Exception {
        start(true);
        server.mode = FakeLdapServer.Mode.HUGE;
        assertEquals("ldap.response_too_large", code(() -> ldaps().bind("alice", "alice-pw".toCharArray())));
    }

    @Test
    @DisplayName("SSO-T-ldap-011 indefinite length and non-minimal length BER rejected")
    void malformedLength() throws Exception {
        start(true);
        server.mode = FakeLdapServer.Mode.INDEFINITE;
        assertEquals("ldap.protocol_error", code(() -> ldaps().bind("alice", "alice-pw".toCharArray())));
        server.mode = FakeLdapServer.Mode.NON_MINIMAL_LENGTH;
        assertEquals("ldap.protocol_error", code(() -> ldaps().bind("alice", "alice-pw".toCharArray())));
    }

    @Test
    @DisplayName("SSO-T-ldap-012 BER decoder rejects truncated, nested-too-deep, non-minimal integer and high tag numbers")
    void berStrict() {
        assertEquals("ldap.protocol_error", code(() -> new BerReader(new byte[] {0x04, 0x05, 0x61}).octets(0x04)));
        assertEquals("ldap.protocol_error", code(() -> new BerReader(new byte[] {0x02, 0x02, 0x00, 0x01}).integer(0x02)));
        assertEquals("ldap.protocol_error", code(() -> new BerReader(new byte[] {0x02, 0x02, (byte) 0xff, (byte) 0x80}).integer(0x02)));
        assertEquals("ldap.protocol_error", code(() -> new BerReader(new byte[] {0x02, 0x00}).integer(0x02)));
        assertEquals("ldap.protocol_error", code(() -> new BerReader(new byte[] {0x1f, 0x01, 0x00}).octets(0x1f)));
        assertEquals("ldap.protocol_error", code(() -> new BerReader(new byte[] {0x04, (byte) 0x81, 0x05, 1, 2, 3, 4, 5}).octets(0x04)));
        assertEquals("ldap.protocol_error", code(() -> new BerReader(new byte[] {0x04, (byte) 0x85, 0, 0, 0, 0, 1, 1}).octets(0x04)));
        assertEquals(-128, new BerReader(new byte[] {0x02, 0x01, (byte) 0x80}).integer(0x02));
        assertEquals(128, new BerReader(new byte[] {0x02, 0x02, 0x00, (byte) 0x80}).integer(0x02));
        byte[] nested = new byte[] {0x30, 0x02, 0x30, 0x00};
        BerReader r = new BerReader(nested);
        r.enter(0x30).enter(0x30).finish();
        byte[] chain = new byte[] {0x30, 0x0e, 0x30, 0x0c, 0x30, 0x0a, 0x30, 0x08, 0x30, 0x06, 0x30, 0x04, 0x30, 0x02, 0x30, 0x00};
        BerReader d = new BerReader(chain);
        assertEquals("ldap.protocol_error", code(() -> {
            BerReader x = d;
            for (int i = 0; i < 9; i++) {
                x = x.enter(0x30);
            }
        }));
    }

    @Test
    @DisplayName("SSO-T-ldap-013 BER and message encoding vectors")
    void vectors() {
        byte[] bind = Ber.tlv(Ber.SEQUENCE, Ber.integer(1), LdapMessages.bindRequest("", new char[0]));
        assertEquals("300c02010160070201030400" + "8000", hex(bind));
        byte[] f = Filter.and(List.of(Filter.present("objectClass"), Filter.equal("uid", "a"))).encode();
        assertEquals("a017870b6f626a656374436c617373" + "a30804037569640401" + "61", hex(f));
        assertEquals("020100", hex(Ber.integer(0)));
        assertEquals("020200ff", hex(Ber.integer(255)));
        assertEquals("0201ff", hex(Ber.integer(-1)));
        assertEquals("02028000", hex(Ber.integer(-32768)));
        assertEquals("8201" + "00", hex(Ber.length(256)));
        assertEquals("7f", hex(Ber.length(127)));
        assertEquals("8180", hex(Ber.length(128)));
    }

    @Test
    @DisplayName("SSO-T-ldap-014 referrals in search results are ignored and never followed")
    void referral() throws Exception {
        start(true);
        server.mode = FakeLdapServer.Mode.REFERRAL;
        DirectoryConnector.DirectoryUser u = ldaps().bind("alice", "alice-pw".toCharArray());
        assertEquals(ALICE, u.dn());
        assertEquals(2, server.connections.get());
    }

    @Test
    @DisplayName("SSO-T-ldap-015 plain connection refused by configuration unless the test flag is set")
    void plainRejected() throws Exception {
        start(false);
        assertEquals("ldap.tls_required", code(() -> configRaw(LdapConfig.TlsMode.PLAIN)));
        LdapDirectoryConnector c = new LdapDirectoryConnector(LdapConfig.plainForTests("127.0.0.1", server.port(), "dc=ex", FakeLdapServer.SERVICE_DN, FakeLdapServer.SERVICE_PW.toCharArray()));
        assertEquals(ALICE, c.bind("alice", "alice-pw".toCharArray()).dn());
        assertTrue(server.binds.stream().noneMatch(FakeLdapServer.BindSeen::tls));
    }

    @Test
    @DisplayName("SSO-T-ldap-016 StartTLS upgrades before any bind is sent")
    void startTls() throws Exception {
        start(false);
        LdapDirectoryConnector c = new LdapDirectoryConnector(config(LdapConfig.TlsMode.START_TLS));
        assertEquals(ALICE, c.bind("alice", "alice-pw".toCharArray()).dn());
        assertTrue(server.binds.stream().allMatch(FakeLdapServer.BindSeen::tls));
    }

    @Test
    @DisplayName("SSO-T-ldap-017 StartTLS refused by the server aborts and no password is sent")
    void startTlsRefused() throws Exception {
        start(false);
        server.startTlsOk = false;
        LdapDirectoryConnector c = new LdapDirectoryConnector(config(LdapConfig.TlsMode.START_TLS));
        assertEquals("ldap.starttls_failed", code(() -> c.bind("alice", "alice-pw".toCharArray())));
        assertTrue(server.binds.isEmpty());
    }

    @Test
    @DisplayName("SSO-T-ldap-018 LDAPS certificate for another host or untrusted fails")
    void tlsVerification() throws Exception {
        server = new FakeLdapServer(otherTls, true);
        server.add(ALICE, "alice-pw", FakeLdapServer.attrs("uid", "alice"));
        LdapConfig mismatch = config(LdapConfig.TlsMode.LDAPS).withSslSocketFactory(otherTls.client.getSocketFactory());
        assertEquals("ldap.tls_failed", code(() -> new LdapDirectoryConnector(mismatch).bind("alice", "alice-pw".toCharArray())));
        LdapConfig untrusted = config(LdapConfig.TlsMode.LDAPS);
        assertEquals("ldap.tls_failed", code(() -> new LdapDirectoryConnector(untrusted).bind("alice", "alice-pw".toCharArray())));
        assertTrue(server.binds.isEmpty());
    }

    @Test
    @DisplayName("SSO-T-ldap-019 Active Directory UPN bind uses the userPrincipalName of the found entry")
    void upnBind() throws Exception {
        start(true);
        LdapDirectoryConnector c = new LdapDirectoryConnector(config(LdapConfig.TlsMode.LDAPS).withBindMode(LdapConfig.BindMode.UPN, "ad.ex.test"));
        assertEquals(ALICE, c.bind("alice", "alice-pw".toCharArray()).dn());
        assertEquals("alice@ad.ex.test", server.binds.get(1).name());
    }

    @Test
    @DisplayName("SSO-T-ldap-020 UPN bind without UPN attribute binds the found entry DN, never the typed username or suffix")
    void upnFallback() throws Exception {
        start(true);
        LdapConfig noSuffix = config(LdapConfig.TlsMode.LDAPS).withBindMode(LdapConfig.BindMode.UPN, null);
        assertEquals("uid=bob,ou=people,dc=ex", new LdapDirectoryConnector(noSuffix).bind("bob", "bob-pw".toCharArray()).dn());
        assertEquals("uid=bob,ou=people,dc=ex", server.binds.get(1).name());
    }

    @Test
    @DisplayName("SSO-T-ldap-031 UPN mode: entry found by mail without UPN cannot be bound as another account named by the input")
    void upnInputNeverUsedAsBindName() throws Exception {
        start(true);
        server.add("uid=mallory,ou=people,dc=ex", "mallory-pw", FakeLdapServer.attrs("uid", "mallory", "userPrincipalName", "bob@ex.test"));
        LdapConfig upn = config(LdapConfig.TlsMode.LDAPS).withBindMode(LdapConfig.BindMode.UPN, "ex.test");
        int before = server.binds.size();
        assertEquals("ldap.invalid_credentials", code(() -> new LdapDirectoryConnector(upn).bind("bob@ex.test", "mallory-pw".toCharArray())));
        assertTrue(server.binds.subList(before, server.binds.size()).stream().noneMatch(b -> b.name().equals("bob@ex.test")));
    }

    @Test
    @DisplayName("SSO-T-ldap-032 directory search refuses a base DN outside the configured base")
    void searchBaseConfined() throws Exception {
        start(true);
        assertEquals("ldap.base_dn_outside", code(() -> ldaps().search(new DirectoryConnector.SearchQuery("dc=other", "mail", "bob@ex.test", List.of("uid"), 5))));
        assertEquals("ldap.base_dn_outside", code(() -> ldaps().search(new DirectoryConnector.SearchQuery("dc=exx", "mail", "bob@ex.test", List.of("uid"), 5))));
        assertEquals(1, ldaps().search(new DirectoryConnector.SearchQuery("ou=people, dc=ex", "mail", "bob@ex.test", List.of("uid"), 5)).size());
    }

    @Test
    @DisplayName("SSO-T-ldap-033 base DN confinement compares parsed RDNs: escaped commas and spaces inside values never pass")
    void searchBaseRdnAware() throws Exception {
        start(true);
        for (String bad : List.of("ou=x\\,dc=ex", "d c=ex", "dc=e x", "ou=a,dc=e\\x", "ou=\"a,dc=ex\"", "ou=a\\", ",dc=ex", "ou=a,,dc=ex")) {
            assertEquals("ldap.base_dn_outside", code(() -> ldaps().search(new DirectoryConnector.SearchQuery(bad, "mail", "bob@ex.test",
                    List.of("uid"), 5))), bad);
        }
        assertEquals(1, ldaps().search(new DirectoryConnector.SearchQuery("OU=people , DC=ex", "mail", "bob@ex.test", List.of("uid"), 5)).size());
    }

    @Test
    @DisplayName("SSO-T-ldap-021 service account bind failure surfaces as service_bind_failed")
    void serviceBindFails() throws Exception {
        start(true);
        LdapConfig bad = LdapConfig.of("127.0.0.1", server.port(), LdapConfig.TlsMode.LDAPS, "dc=ex", FakeLdapServer.SERVICE_DN, "wrong".toCharArray())
                .withSslSocketFactory(goodTls.client.getSocketFactory());
        assertEquals("ldap.service_bind_failed", code(() -> new LdapDirectoryConnector(bad).bind("alice", "alice-pw".toCharArray())));
    }

    @Test
    @DisplayName("SSO-T-ldap-022 silent server hits the operation timeout")
    void timeout() throws Exception {
        start(true);
        server.mode = FakeLdapServer.Mode.SILENT_SEARCH;
        LdapConfig fast = config(LdapConfig.TlsMode.LDAPS).withTimeouts(Duration.ofSeconds(2), Duration.ofMillis(700));
        assertEquals("ldap.timeout", code(() -> new LdapDirectoryConnector(fast).bind("alice", "alice-pw".toCharArray())));
    }

    @Test
    @DisplayName("SSO-T-ldap-023 trickled partial frame hits the total deadline")
    void trickle() throws Exception {
        start(true);
        server.mode = FakeLdapServer.Mode.TRICKLE_SEARCH;
        LdapConfig fast = config(LdapConfig.TlsMode.LDAPS).withTimeouts(Duration.ofSeconds(2), Duration.ofMillis(700));
        String c = code(() -> new LdapDirectoryConnector(fast).bind("alice", "alice-pw".toCharArray()));
        assertTrue(c.equals("ldap.timeout") || c.equals("ldap.protocol_error"), c);
    }

    @Test
    @DisplayName("SSO-T-ldap-024 mismatched or unsolicited message id rejected")
    void wrongId() throws Exception {
        start(true);
        server.mode = FakeLdapServer.Mode.WRONG_ID;
        assertEquals("ldap.protocol_error", code(() -> ldaps().bind("alice", "alice-pw".toCharArray())));
        server.mode = FakeLdapServer.Mode.UNSOLICITED;
        assertEquals("ldap.protocol_error", code(() -> ldaps().bind("alice", "alice-pw".toCharArray())));
    }

    @Test
    @DisplayName("SSO-T-ldap-025 invalid usernames rejected without network use")
    void usernameInvalid() throws Exception {
        start(true);
        assertEquals("ldap.username_invalid", code(() -> ldaps().bind("", "x".toCharArray())));
        assertEquals("ldap.username_invalid", code(() -> ldaps().bind("a".repeat(300), "x".toCharArray())));
        assertEquals("ldap.username_invalid", code(() -> ldaps().bind("a\u0000b", "x".toCharArray())));
        assertEquals(0, server.connections.get());
    }

    @Test
    @DisplayName("SSO-T-ldap-026 a fresh connection per auth with unbind sent and passwords absent from config text and errors")
    void perAuth() throws Exception {
        start(true);
        LdapDirectoryConnector c = ldaps();
        c.bind("alice", "alice-pw".toCharArray());
        c.bind("bob", "bob-pw".toCharArray());
        assertEquals(4, server.connections.get());
        Thread.sleep(300);
        assertTrue(server.unbinds.get() >= 3);
        assertFalse(config(LdapConfig.TlsMode.LDAPS).toString().contains(FakeLdapServer.SERVICE_PW));
        SsoException e = assertThrows(SsoException.class, () -> c.bind("alice", "wrong-secret-xyz".toCharArray()));
        assertFalse(e.getMessage().contains("wrong-secret-xyz"));
    }

    @Test
    @DisplayName("SSO-T-ldap-027 DirectoryConnector.search runs under the service account and returns entries")
    void searchApi() throws Exception {
        start(true);
        List<DirectoryConnector.DirectoryUser> r = ldaps().search(new DirectoryConnector.SearchQuery("dc=ex", "mail", "bob@ex.test", List.of("uid"), 5));
        assertEquals(1, r.size());
        assertEquals("uid=bob,ou=people,dc=ex", r.get(0).dn());
        assertEquals(5, server.searches.get(0).sizeLimit());
        assertEquals("ldap.attribute_invalid", code(() -> ldaps().search(new DirectoryConnector.SearchQuery("dc=ex", "mail)(x", "v", List.of("uid"), 5))));
    }

    @Test
    @DisplayName("SSO-T-ldap-028 config rejects empty service password and invalid login attribute")
    void configValidation() {
        assertEquals("ldap.config_invalid", code(() -> LdapConfig.of("h", 636, LdapConfig.TlsMode.LDAPS, "dc=ex", "cn=svc", new char[0])));
        assertEquals("ldap.config_invalid", code(() -> LdapConfig.of("h", 636, LdapConfig.TlsMode.LDAPS, "dc=ex", "cn=svc", "p".toCharArray()).withLoginAttributes(List.of("uid)(x"))));
    }

    @Test
    @DisplayName("SSO-T-ldap-029 F-R2-3 server size limit that truncates the user lookup to one entry is treated as ambiguous")
    void adminSizeLimitAmbiguity() throws Exception {
        start(true);
        server.users.add(0, new FakeLdapServer.User("uid=mallory,ou=people,dc=ex",
                FakeLdapServer.attrs("uid", "mallory", "mail", "alice"), "mallory-pw"));
        server.adminSizeLimit = 1;
        assertEquals("ldap.ambiguous_user", code(() -> ldaps().bind("alice", "mallory-pw".toCharArray())));
        assertTrue(server.binds.stream().noneMatch(b -> b.name().startsWith("uid=mallory")));
    }

    @Test
    @DisplayName("SSO-T-ldap-030 F-R2-4 unknown user costs the same directory round trips as a wrong password")
    void unknownUserTiming() throws Exception {
        start(true);
        assertEquals("ldap.invalid_credentials", code(() -> ldaps().bind("nobody", "x".toCharArray())));
        int unknownConnections = server.connections.get();
        int unknownBinds = server.binds.size();
        assertEquals("ldap.invalid_credentials", code(() -> ldaps().bind("alice", "nope".toCharArray())));
        assertEquals(unknownConnections, server.connections.get() - unknownConnections);
        assertEquals(unknownBinds, server.binds.size() - unknownBinds);
        FakeLdapServer.BindSeen dummy = server.binds.get(1);
        assertTrue(server.users.stream().noneMatch(u -> u.dn().equalsIgnoreCase(dummy.name())));
        assertEquals(1, dummy.passwordLength());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
