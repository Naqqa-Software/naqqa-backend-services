package com.naqqa.auth.sso.saml;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.flow.AccountLinkService;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.SsoEnabledTestBase;
import com.naqqa.auth.support.TestSamlIdp;
import com.naqqa.sso.saml.InMemoryReplayCache;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.saml.SamlBindings;
import com.naqqa.sso.saml.SamlPostMessage;
import com.naqqa.sso.saml.dsig.XmlSignatureVerifier;
import com.naqqa.sso.saml.idp.AuthnRequestInfo;
import com.naqqa.sso.saml.idp.InMemoryServiceProviderRegistry;
import com.naqqa.sso.saml.idp.SamlIdp;
import com.naqqa.sso.saml.idp.SamlIdpConfig;
import com.naqqa.sso.saml.idp.SamlSubject;
import com.naqqa.sso.saml.idp.ServiceProvider;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlParser;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlSpEndpointsTest extends SsoEnabledTestBase {

    static final String IDP_ENTITY = "https://idp.example.org/saml";
    static final TestSamlIdp IDP = new TestSamlIdp(IDP_ENTITY);
    static final TestSamlIdp NEXT = new TestSamlIdp(IDP_ENTITY);
    static final TestSamlIdp ROGUE = new TestSamlIdp(IDP_ENTITY);
    static final String FRONT = "http://localhost:4200";
    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    SamlSpIdpStore idps;

    static final class Cfg {
        final String tenant;
        final String domain;
        boolean enabled = true;
        List<String> certificates = new ArrayList<>(List.of(IDP.pem()));
        String nameIdFormat = Saml.NAMEID_PERSISTENT;
        List<String> allowed;
        List<String> authoritative;
        boolean jit = true;
        Map<String, String> groupRoles = Map.of();
        String defaultRole;
        boolean trusted;
        boolean unsolicited;
        boolean signedResponse;

        Cfg(String tenant, String domain) {
            this.tenant = tenant;
            this.domain = domain;
            this.allowed = List.of(domain);
            this.authoritative = List.of(domain);
        }

        String sp() {
            return "http://localhost/saml2/sp/" + tenant + "/metadata";
        }

        String acs() {
            return "http://localhost/saml2/sp/" + tenant + "/acs";
        }

        String email(String tag) {
            return tag + "-" + SEQ.incrementAndGet() + "@" + domain;
        }
    }

    record Started(String requestId, String rawQuery) {
    }

    Cfg tenant(String tag) {
        String id = tag + "-" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        registeredTenant(id);
        return new Cfg(id, id + ".example.org");
    }

    Cfg save(Cfg c) {
        idps.save(new SamlSpIdpStore.IdpSettings(c.tenant, true, c.enabled, "Acme", IDP_ENTITY, "https://idp.example.org/sso", c.certificates,
                c.nameIdFormat, "email", "name", "groups", c.allowed, c.authoritative, c.jit, c.groupRoles, c.defaultRole, c.trusted,
                List.of(Saml.AC_MFA), c.unsolicited, true, c.signedResponse, false, clock.instant(), "test"));
        return c;
    }

    static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    static String location(MvcResult r) {
        return r.getResponse().getHeader("Location");
    }

    static MvcResult begin(Browser b, String tenant, String relay) {
        var req = MockMvcRequestBuilders.get("/saml2/sp/" + tenant + "/login");
        if (relay != null) {
            req.param("relay", relay);
        }
        return b.perform(req);
    }

    Started start(Browser b, Cfg c, String relay) {
        MvcResult r = begin(b, c.tenant, relay);
        assertEquals(302, status(r), content(r));
        String loc = location(r);
        assertTrue(loc.startsWith("https://idp.example.org/sso?SAMLRequest="), loc);
        String raw = URI.create(loc).getRawQuery();
        SamlBindings.RedirectMessage m = SamlBindings.decodeRedirect(raw, "SAMLRequest", SamlBindings.MAX_INFLATED);
        XmlDocument doc = new XmlParser().parse(m.xml());
        return new Started(doc.root().attribute("ID"), raw);
    }

    MvcResult acs(Browser b, Cfg c, String response, String relayState) {
        var req = MockMvcRequestBuilders.post("/saml2/sp/" + c.tenant + "/acs").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", "https://idp.example.org").param("SAMLResponse", response);
        if (relayState != null) {
            req.param("RelayState", relayState);
        }
        return b.perform(req);
    }

    TestSamlIdp.Spec spec(Cfg c, String inResponseTo, String subject, String email) {
        return IDP.spec(c.sp(), c.acs(), inResponseTo, subject, email, clock.instant());
    }

    static void failed(MvcResult r, String code) {
        assertEquals(302, status(r), content(r));
        assertEquals(FRONT + "/login?error=" + code, location(r));
    }

    static void loggedIn(MvcResult r, Browser b, String relay) {
        assertEquals(302, status(r), content(r));
        assertEquals(FRONT + relay, location(r));
        assertTrue(b.cookies().containsKey("access_token"), b.cookies().toString());
    }

    List<String> auditReasons(String action, String tenant) {
        List<String> out = new ArrayList<>();
        for (Document d : mongo.find(new Query(Criteria.where("action").is(action)), Document.class, "sso_audit")) {
            Map<String, String> attrs = new java.util.HashMap<>();
            for (Document a : d.getList("attributes", Document.class, List.of())) {
                attrs.put(a.getString("k"), a.getString("v"));
            }
            if (tenant.equals(attrs.get("tenant"))) {
                out.add(attrs.getOrDefault("reason", d.getString("outcome")));
            }
        }
        return out;
    }

    static X509Certificate spCertificate() throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(new ClassPathResource("saml/sp-signing.crt.pem").getInputStream().readAllBytes()));
    }

    MvcResult signIn(Browser b, Cfg c, String subject, String email, String relay) {
        Started s = start(b, c, relay);
        return acs(b, c, IDP.response(spec(c, s.requestId(), subject, email)), null);
    }

    @Test
    @DisplayName("SSO-T-auth-664 SAML SP round trip with the core SamlIdp: signed AuthnRequest, signed assertion, JIT account, session, request id single use, metadata")
    void roundTrip() throws Exception {
        Cfg c = save(tenant("rt"));
        X509Certificate spCert = spCertificate();
        SamlIdp idp = new SamlIdp(new SamlIdpConfig(IDP_ENTITY, "https://idp.example.org/sso", null, IDP.keys.getPrivate(), IDP.certificate,
                Bytes.random(32), null, null, null, null, null, true),
                new InMemoryServiceProviderRegistry().register(new ServiceProvider(c.sp(), List.of(c.acs()), null, List.of(spCert), null,
                        true, false, false, Saml.NAMEID_PERSISTENT, Set.of(Saml.NAMEID_PERSISTENT))),
                new InMemoryReplayCache(clock), clock);
        Browser b = browser();
        MvcResult begin = begin(b, c.tenant, "/dashboard?tab=1");
        assertEquals(302, status(begin), content(begin));
        String cookie = begin.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SamlSpController.BINDING_COOKIE + "=")).findFirst().orElseThrow();
        assertTrue(cookie.contains("SameSite=None") && cookie.contains("Secure") && cookie.contains("HttpOnly")
                && cookie.contains("Path=/saml2/sp/" + c.tenant + "/acs"), cookie);
        String raw = URI.create(location(begin)).getRawQuery();
        SamlBindings.verifyRedirectSignature(SamlBindings.decodeRedirect(raw, "SAMLRequest", SamlBindings.MAX_INFLATED),
                new XmlSignatureVerifier(List.of(spCert)));
        AuthnRequestInfo info = idp.parseAuthnRequestRedirect(raw);
        assertTrue(info.signed());
        assertEquals(c.acs(), info.assertionConsumerServiceUrl());
        String email = c.email("jit");
        SamlPostMessage post = idp.issueResponse(info, new SamlSubject("idp-user-1", email, clock.instant(), "pwd", "s-1",
                Map.of("email", List.of(email), "name", List.of("Ana Pop"))));
        MvcResult done = acs(b, c, post.value(), post.relayState());
        loggedIn(done, b, "/dashboard?tab=1");
        UserEntity user = userRepository.findByEmail(email).orElseThrow();
        assertTrue(user.isEmailVerified());
        assertEquals("Ana Pop", user.getFullName());
        assertTrue(auditReasons("saml.sp.signup", c.tenant).contains("success"));
        assertTrue(auditReasons("saml.sp.login", c.tenant).contains("success"));
        assertNull(mongo.findById(c.tenant + "|" + info.id(), Document.class, MongoSamlRequestStore.COLLECTION));
        Browser again = browser();
        failed(acs(again, c, post.value(), null), "SAML_FAILED");
        b.cookie(SamlSpController.BINDING_COOKIE, "replayed");
        failed(acs(b, c, post.value(), null), "SAML_FAILED");
        MvcResult md = browser().get("/saml2/sp/" + c.tenant + "/metadata");
        assertEquals(200, status(md));
        String xml = content(md);
        assertTrue(xml.contains("entityID=\"" + c.sp() + "\"") && xml.contains("AuthnRequestsSigned=\"true\"")
                && xml.contains("Location=\"" + c.acs() + "\""), xml);
    }

    @Test
    @DisplayName("SSO-T-auth-665 replayed assertions are refused: same response in a new flow, in another browser and after the request was used")
    void replay() {
        Cfg c = save(tenant("rp"));
        Browser b = browser();
        Started s = start(b, c, "/a");
        String resp = IDP.response(spec(c, s.requestId(), "replay-" + SEQ.incrementAndGet(), c.email("replay")));
        loggedIn(acs(b, c, resp, null), b, "/a");
        Browser other = browser();
        start(other, c, "/b");
        failed(acs(other, c, resp, null), "SAML_FAILED");
        failed(acs(browser(), c, resp, null), "SAML_FAILED");
        List<String> reasons = auditReasons("saml.sp.acs", c.tenant);
        assertTrue(reasons.contains("SAML_IN_RESPONSE_TO") || reasons.contains("SAML_REPLAY"), reasons.toString());
        assertTrue(reasons.contains("SAML_UNSOLICITED"), reasons.toString());
    }

    @Test
    @DisplayName("SSO-T-auth-666 InResponseTo must equal the request id bound to this browser; a response for another browser's request is refused")
    void wrongInResponseTo() {
        Cfg c = save(tenant("irt"));
        Browser victim = browser();
        start(victim, c, "/v");
        Browser attacker = browser();
        Started mine = start(attacker, c, "/x");
        String forAttacker = IDP.response(spec(c, mine.requestId(), "att-" + SEQ.incrementAndGet(), c.email("att")));
        failed(acs(victim, c, forAttacker, null), "SAML_FAILED");
        assertFalse(victim.cookies().containsKey("access_token"));
        Browser b = browser();
        start(b, c, "/w");
        failed(acs(b, c, IDP.response(spec(c, "_" + Bytes.randomToken(20), "w-" + SEQ.incrementAndGet(), c.email("w"))), null), "SAML_FAILED");
        Browser n = browser();
        Started s = start(n, c, "/n");
        TestSamlIdp.Spec noIrt = spec(c, s.requestId(), "n-" + SEQ.incrementAndGet(), c.email("n"));
        noIrt.inResponseTo = null;
        failed(acs(n, c, IDP.response(noIrt), null), "SAML_FAILED");
        assertTrue(auditReasons("saml.sp.acs", c.tenant).stream().filter("SAML_IN_RESPONSE_TO"::equals).count() >= 3,
                auditReasons("saml.sp.acs", c.tenant).toString());
    }

    @Test
    @DisplayName("SSO-T-auth-667 unsolicited responses are refused unless the IdP allows them; then RelayState must be a relative path")
    void unsolicited() {
        Cfg c = save(tenant("uns"));
        String email = c.email("uns");
        failed(acs(browser(), c, IDP.response(spec(c, null, "uns-" + SEQ.incrementAndGet(), email)), "/x"), "SAML_FAILED");
        assertTrue(auditReasons("saml.sp.acs", c.tenant).contains("SAML_UNSOLICITED"));
        c.unsolicited = true;
        save(c);
        Browser b = browser();
        loggedIn(acs(b, c, IDP.response(spec(c, null, "uns-" + SEQ.incrementAndGet(), c.email("uns"))), "/inbox"), b, "/inbox");
        Browser evil = browser();
        loggedIn(acs(evil, c, IDP.response(spec(c, null, "uns-" + SEQ.incrementAndGet(), c.email("uns"))), "https://evil.example/x"), evil, "/");
        Browser bound = browser();
        start(bound, c, "/kept");
        failed(acs(bound, c, IDP.response(spec(c, null, "uns-" + SEQ.incrementAndGet(), c.email("uns"))), "/other"), "SAML_FAILED");
        String once = IDP.response(spec(c, null, "uns-" + SEQ.incrementAndGet(), c.email("uns")));
        Browser first = browser();
        loggedIn(acs(first, c, once, "/once"), first, "/once");
        Browser second = browser();
        failed(acs(second, c, once, "/once"), "SAML_FAILED");
        assertFalse(second.cookies().containsKey("access_token"));
        assertTrue(auditReasons("saml.sp.acs", c.tenant).contains("SAML_REPLAY"), auditReasons("saml.sp.acs", c.tenant).toString());
    }

    @Test
    @DisplayName("SSO-T-auth-668 wrong audience, recipient, destination or issuer are refused")
    void audienceAndRecipient() {
        Cfg c = save(tenant("aud"));
        Cfg other = save(tenant("aud2"));
        List<java.util.function.Consumer<TestSamlIdp.Spec>> attacks = List.of(
                s -> s.audience = other.sp(),
                s -> s.recipient = other.acs(),
                s -> s.destination = other.acs(),
                s -> s.issuer = "https://other-idp.example.org");
        for (var attack : attacks) {
            Browser b = browser();
            Started st = start(b, c, "/a");
            TestSamlIdp.Spec s = spec(c, st.requestId(), "aud-" + SEQ.incrementAndGet(), c.email("aud"));
            attack.accept(s);
            failed(acs(b, c, IDP.response(s), null), "SAML_FAILED");
            assertFalse(b.cookies().containsKey("access_token"));
        }
        List<String> reasons = auditReasons("saml.sp.acs", c.tenant);
        assertTrue(reasons.containsAll(List.of("SAML_AUDIENCE", "SAML_RECIPIENT", "SAML_DESTINATION", "SAML_ISSUER")), reasons.toString());
        Browser b = browser();
        Started st = start(b, other, "/o");
        failed(acs(b, c, IDP.response(spec(other, st.requestId(), "x-" + SEQ.incrementAndGet(), other.email("x"))), null), "SAML_FAILED");
    }

    @Test
    @DisplayName("SSO-T-auth-669 signature wrapping and tampering through the ACS: injected assertion, edited NameID, unsigned or foreign-key assertion")
    void signatureWrapping() {
        Cfg c = save(tenant("xsw"));
        UserEntity victim = user(c.email("victim"));
        Browser b1 = browser();
        Started s1 = start(b1, c, "/a");
        TestSamlIdp.Spec good = spec(c, s1.requestId(), "attacker-" + SEQ.incrementAndGet(), c.email("attacker"));
        String signed = IDP.assertionXml(good);
        TestSamlIdp.Spec evilSpec = spec(c, s1.requestId(), "victim-" + SEQ.incrementAndGet(), victim.getEmail());
        evilSpec.signAssertion = false;
        String evil = IDP.assertionXml(evilSpec);
        String wrapped = IDP.responseXml(good, evil.replace("</saml:Conditions>", "</saml:Conditions><saml:Advice>" + signed + "</saml:Advice>"));
        failed(acs(b1, c, TestSamlIdp.encode(wrapped), null), "SAML_FAILED");
        Browser b2 = browser();
        Started s2 = start(b2, c, "/a");
        TestSamlIdp.Spec g2 = spec(c, s2.requestId(), "edit-" + SEQ.incrementAndGet(), c.email("edit"));
        String edited = IDP.responseXml(g2, IDP.assertionXml(g2).replace(">" + g2.nameId + "<", ">victim-subject<"));
        failed(acs(b2, c, TestSamlIdp.encode(edited), null), "SAML_FAILED");
        Browser b3 = browser();
        Started s3 = start(b3, c, "/a");
        TestSamlIdp.Spec two = spec(c, s3.requestId(), "two-" + SEQ.incrementAndGet(), c.email("two"));
        String twice = IDP.responseXml(two, IDP.assertionXml(two) + evil);
        failed(acs(b3, c, TestSamlIdp.encode(twice), null), "SAML_FAILED");
        Browser b4 = browser();
        Started s4 = start(b4, c, "/a");
        TestSamlIdp.Spec unsigned = spec(c, s4.requestId(), "u-" + SEQ.incrementAndGet(), c.email("u"));
        unsigned.signAssertion = false;
        failed(acs(b4, c, IDP.response(unsigned), null), "SAML_FAILED");
        Browser b5 = browser();
        Started s5 = start(b5, c, "/a");
        failed(acs(b5, c, ROGUE.response(ROGUE.spec(c.sp(), c.acs(), s5.requestId(), "r-" + SEQ.incrementAndGet(), c.email("r"),
                clock.instant())), null), "SAML_FAILED");
        Browser b6 = browser();
        start(b6, c, "/a");
        failed(acs(b6, c, "PD94bWwgdmVyc2lvbj0iMS4wIj8+PCFET0NUWVBFIHg+PHgvPg==", null), "SAML_FAILED");
        for (Browser b : List.of(b1, b2, b3, b4, b5, b6)) {
            assertFalse(b.cookies().containsKey("access_token"));
        }
        assertTrue(userRepository.findByEmail(victim.getEmail()).isPresent());
        assertEquals(0, mongo.count(new Query(Criteria.where("userId").is(String.valueOf(victim.getId()))), AccountLinkService.COLLECTION));
    }

    @Test
    @DisplayName("SSO-T-auth-670 IdP signing certificate rotation: both configured certificates verify, a removed one stops at once")
    void certificateRotation() {
        Cfg c = tenant("rot");
        c.certificates = new ArrayList<>(List.of(IDP.pem(), NEXT.pem()));
        save(c);
        String subject = "rot-" + SEQ.incrementAndGet();
        String email = c.email("rot");
        Browser device = browser();
        for (TestSamlIdp key : List.of(IDP, NEXT)) {
            Browser b = device;
            Started s = start(b, c, "/r");
            loggedIn(acs(b, c, key.response(key.spec(c.sp(), c.acs(), s.requestId(), subject, email, clock.instant())), null), b, "/r");
        }
        c.certificates = new ArrayList<>(List.of(NEXT.pem()));
        save(c);
        Browser b = device;
        Started s = start(b, c, "/r");
        failed(acs(b, c, IDP.response(IDP.spec(c.sp(), c.acs(), s.requestId(), subject, email, clock.instant())), null), "SAML_FAILED");
        assertTrue(auditReasons("saml.sp.acs", c.tenant).stream().anyMatch(r -> r.startsWith("DSIG")), auditReasons("saml.sp.acs", c.tenant).toString());
    }

    @Test
    @DisplayName("SSO-T-auth-671 email domains: not allowed is refused, JIT off is refused, a non-authoritative domain creates an unverified account")
    void domains() {
        Cfg c = tenant("dom");
        String second = "second-" + c.domain;
        c.allowed = List.of(c.domain, second);
        save(c);
        Browser b = browser();
        String foreign = "x-" + SEQ.incrementAndGet() + "@not-allowed.example.org";
        failed(signIn(b, c, "f-" + SEQ.incrementAndGet(), foreign, "/d"), "DOMAIN_NOT_ALLOWED");
        assertTrue(userRepository.findByEmail(foreign).isEmpty());
        failed(signIn(browser(), c, "m-" + SEQ.incrementAndGet(), null, "/d"), "EMAIL_MISSING");
        String weak = "w-" + SEQ.incrementAndGet() + "@" + second;
        Browser w = browser();
        loggedIn(signIn(w, c, "w-" + SEQ.incrementAndGet(), weak, "/d"), w, "/d");
        assertFalse(userRepository.findByEmail(weak).orElseThrow().isEmailVerified());
        c.jit = false;
        save(c);
        String none = c.email("nojit");
        failed(signIn(browser(), c, "n-" + SEQ.incrementAndGet(), none, "/d"), "SIGNUP_DISABLED");
        assertTrue(userRepository.findByEmail(none).isEmpty());
        assertTrue(auditReasons("saml.sp.login", c.tenant).containsAll(List.of("domain_not_allowed", "jit_disabled", "email_missing")));
    }

    @Test
    @DisplayName("SSO-T-auth-672 adoption by email: plain verified account in an authoritative domain is linked; admin, unverified, TOTP and non-authoritative targets are refused")
    void adoption() {
        Cfg c = tenant("adopt");
        String second = "second-" + c.domain;
        c.allowed = List.of(c.domain, second);
        save(c);
        UserEntity plain = user(c.email("plain"));
        Browser b = browser();
        loggedIn(signIn(b, c, "p-" + SEQ.incrementAndGet(), plain.getEmail(), "/p"), b, "/p");
        assertTrue(auditReasons("saml.sp.adopt", c.tenant).contains("success"));
        UserEntity admin = createUser(c.email("admin"), passwordService.hash(PASSWORD), role("SAML_SP_ADMIN", "SSO_ADMIN_USERS"));
        failed(signIn(browser(), c, "a-" + SEQ.incrementAndGet(), admin.getEmail(), "/p"), "LINK_REQUIRES_LOGIN");
        UserEntity unverified = user(c.email("unverified"));
        unverified.setEmailVerified(false);
        userRepository.save(unverified);
        failed(signIn(browser(), c, "u-" + SEQ.incrementAndGet(), unverified.getEmail(), "/p"), "LINK_REQUIRES_LOGIN");
        UserEntity totp = user(c.email("totp"));
        enrollTotp(loggedIn(totp.getEmail()));
        failed(signIn(browser(), c, "t-" + SEQ.incrementAndGet(), totp.getEmail(), "/p"), "LINK_REQUIRES_LOGIN");
        UserEntity weak = user("weak-" + SEQ.incrementAndGet() + "@" + second);
        failed(signIn(browser(), c, "w-" + SEQ.incrementAndGet(), weak.getEmail(), "/p"), "LINK_REQUIRES_LOGIN");
        List<String> reasons = auditReasons("saml.sp.login", c.tenant);
        assertTrue(reasons.containsAll(List.of("target_protected", "local_email_unverified", "stronger_factors", "domain_not_authoritative")),
                reasons.toString());
        for (UserEntity u : List.of(admin, unverified, totp, weak)) {
            assertEquals(0, mongo.count(new Query(Criteria.where("userId").is(String.valueOf(u.getId()))), AccountLinkService.COLLECTION));
        }
        assertEquals(1, mongo.count(new Query(Criteria.where("userId").is(String.valueOf(plain.getId()))), AccountLinkService.COLLECTION));
    }

    @Test
    @DisplayName("SSO-T-auth-673 an upstream login never satisfies the local MFA unless the IdP is trusted for assurance and asserts an MFA context")
    void mfaAccountsNeedTheLocalSecondFactor() {
        Cfg c = save(tenant("mfa"));
        String subject = "mfa-" + SEQ.incrementAndGet();
        String email = c.email("mfa");
        Browser first = browser();
        loggedIn(signIn(first, c, subject, email, "/m"), first, "/m");
        enrollTotp(first);
        for (String ctx : List.of(Saml.AC_PASSWORD_PROTECTED, Saml.AC_MFA)) {
            Browser b = first;
            b.cookie("access_token", null);
            Started s = start(b, c, "/m");
            TestSamlIdp.Spec spec = spec(c, s.requestId(), subject, email);
            spec.authnContext = ctx;
            MvcResult r = acs(b, c, IDP.response(spec), null);
            assertEquals(302, status(r));
            assertTrue(location(r).startsWith(FRONT + "/login#mfaToken="), location(r));
            assertTrue(location(r).contains("methods=totp"), location(r));
            assertFalse(b.cookies().containsKey("access_token"));
        }
        c.trusted = true;
        save(c);
        Browser pwd = first;
        Started s = start(pwd, c, "/m");
        MvcResult r = acs(pwd, c, IDP.response(spec(c, s.requestId(), subject, email)), null);
        assertTrue(location(r).startsWith(FRONT + "/login#mfaToken="), location(r));
        Browser mfa = first;
        mfa.cookie("access_token", null);
        Started s2 = start(mfa, c, "/m");
        TestSamlIdp.Spec spec = spec(c, s2.requestId(), subject, email);
        spec.authnContext = Saml.AC_MFA;
        loggedIn(acs(mfa, c, IDP.response(spec), null), mfa, "/m");
        assertEquals(List.of("fed"), AssuranceEngine.federatedAmr(false, List.of(Saml.AC_MFA), Saml.AC_MFA));
        assertEquals(List.of("fed"), AssuranceEngine.federatedAmr(true, List.of(Saml.AC_MFA), Saml.AC_PASSWORD_PROTECTED));
        assertEquals(AssuranceEngine.POSSESSION_STRONG, AssuranceEngine.amrStrength(List.of("fed", "mfa")));
        assertEquals(AssuranceEngine.POSSESSION_WEAK, AssuranceEngine.amrStrength(List.of("fed", "otp", "mfa")));
        assertEquals(AssuranceEngine.KNOWLEDGE, AssuranceEngine.amrStrength(List.of("fed")));
    }

    @Test
    @DisplayName("SSO-T-auth-674 login guards: relative relay only, unknown or disabled IdP, suspended tenant, client without samlSp")
    void loginGuards() {
        Cfg c = save(tenant("guard"));
        MvcResult abs = begin(browser(), c.tenant, "https://evil.example/");
        assertEquals(400, status(abs));
        assertEquals("RELAY_INVALID", json(abs).get("code").asText());
        assertEquals(400, status(begin(browser(), c.tenant, "//evil.example/")));
        assertEquals(404, status(browser().get("/saml2/sp/unknown-" + SEQ.incrementAndGet() + "/login")));
        assertEquals(404, status(browser().get("/saml2/sp/BAD_TENANT/login")));
        MvcResult noSp = browser().clientId("no-saml-sp").get("/saml2/sp/" + c.tenant + "/login");
        assertEquals(404, status(noSp));
        assertEquals("METHOD_DISABLED", json(noSp).get("code").asText());
        c.enabled = false;
        save(c);
        assertEquals(404, status(browser().get("/saml2/sp/" + c.tenant + "/login")));
        assertEquals(200, status(browser().get("/saml2/sp/" + c.tenant + "/metadata")));
        c.enabled = true;
        save(c);
        mongo.updateFirst(new Query(Criteria.where("_id").is(c.tenant)), new org.springframework.data.mongodb.core.query.Update()
                .set("status", "suspended"), "sso_tenants");
        assertEquals(403, status(browser().get("/saml2/sp/" + c.tenant + "/login")));
        MvcResult methods = browser().get(BASE + "/methods");
        assertTrue(json(methods).get("samlSp").asBoolean());
        assertFalse(json(browser().clientId("no-saml-sp").get(BASE + "/methods")).get("samlSp").asBoolean());
    }

    @Test
    @DisplayName("SSO-T-auth-675 group to role mapping on JIT and later sign-ins; administrative roles are never granted by the IdP")
    void groupRoles() {
        role("SAML_SP_SELLER", "profile:read");
        role("SAML_SP_SUPPORT", "profile:read");
        role("SAML_SP_OPS", "SSO_ADMIN_USERS");
        Cfg c = tenant("roles");
        c.groupRoles = Map.of("sellers", "SAML_SP_SELLER", "support", "SAML_SP_SUPPORT", "ops", "SAML_SP_OPS");
        save(c);
        String subject = "g-" + SEQ.incrementAndGet();
        String email = c.email("roles");
        Browser b = browser();
        Started s = start(b, c, "/g");
        TestSamlIdp.Spec spec = spec(c, s.requestId(), subject, email).attribute("groups", "sellers", "ops");
        loggedIn(acs(b, c, IDP.response(spec), null), b, "/g");
        UserEntity u = userRepository.findByEmail(email).orElseThrow();
        assertEquals(Set.of("SAML_SP_SELLER"), u.getRoles().stream().map(r -> r.getName()).collect(java.util.stream.Collectors.toSet()));
        Browser b2 = b;
        Started s2 = start(b2, c, "/g");
        loggedIn(acs(b2, c, IDP.response(spec(c, s2.requestId(), subject, email).attribute("groups", "support")), null), b2, "/g");
        u = userRepository.findByEmail(email).orElseThrow();
        assertEquals(Set.of("SAML_SP_SUPPORT"), u.getRoles().stream().map(r -> r.getName()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(auditReasons("saml.sp.roles", c.tenant).contains("success"));
    }

    MvcResult linkStart(Browser b, Cfg c) {
        return b.post(BASE + "/account/links/saml/" + c.tenant + "/start", null);
    }

    String linkIntent(Browser b, Cfg c) {
        MvcResult r = linkStart(b, c);
        assertEquals(200, status(r), content(r));
        assertEquals("saml:" + c.tenant, json(r).get("provider").asText());
        String intent = json(r).get("linkIntent").asText();
        assertEquals("http://localhost/saml2/sp/" + c.tenant + "/login?link=" + enc(intent), json(r).get("url").asText());
        return intent;
    }

    Started startLink(Browser b, Cfg c, String intent) {
        MvcResult r = b.perform(MockMvcRequestBuilders.get("/saml2/sp/" + c.tenant + "/login").param("link", intent)
                .param("relay", "/profile/security"));
        assertEquals(302, status(r), content(r));
        String raw = URI.create(location(r)).getRawQuery();
        XmlDocument doc = new XmlParser().parse(SamlBindings.decodeRedirect(raw, "SAMLRequest", SamlBindings.MAX_INFLATED).xml());
        return new Started(doc.root().attribute("ID"), raw);
    }

    long links(UserEntity u) {
        return mongo.count(new Query(Criteria.where("userId").is(String.valueOf(u.getId()))), AccountLinkService.COLLECTION);
    }

    @Test
    @DisplayName("SSO-T-auth-679 F-SP-5 linking an account with TOTP to the tenant IdP: only from its own session after step-up with the strongest factor, single-use intent bound to session and browser, audited and notified, never by email alone")
    void linkWithStepUp() {
        role("SAML_SP_LINKED", "profile:read");
        Cfg c = tenant("link");
        c.groupRoles = Map.of("staff", "SAML_SP_LINKED");
        save(c);
        UserEntity u = user(uniqueEmail("link"));
        Browser strong = loggedIn(u.getEmail());
        String secret = enrollTotp(strong).secret();
        Browser weak = fullyLoggedIn(u.getEmail(), secret);
        String subject = "lnk-" + SEQ.incrementAndGet();
        String corporate = c.email("corp");
        failed(signIn(browser(), c, "e-" + SEQ.incrementAndGet(), u.getEmail(), "/p"), "DOMAIN_NOT_ALLOWED");

        clock.advance(java.time.Duration.ofMinutes(11));
        MvcResult stale = linkStart(strong, c);
        assertEquals(401, status(stale), content(stale));
        assertEquals("STEP_UP_REQUIRED", json(stale).get("code").asText());
        MvcResult tooWeak = strong.post(BASE + "/step-up", Map.of("method", "password", "code", PASSWORD));
        assertEquals(400, status(tooWeak), content(tooWeak));
        assertEquals("STEP_UP_FACTOR_TOO_WEAK", json(tooWeak).get("code").asText());
        assertEquals(401, status(linkStart(strong, c)));
        MvcResult stepUp = strong.post(BASE + "/step-up", Map.of("method", "totp", "code", totpCode(secret)));
        assertEquals(200, status(stepUp), content(stepUp));
        nextTotpStep();
        assertEquals(401, status(linkStart(browser(), c)));
        assertEquals(404, status(strong.post(BASE + "/account/links/saml/unknown-" + SEQ.incrementAndGet() + "/start", null)));
        assertEquals(403, status(strong.autoCsrf(false).post(BASE + "/account/links/saml/" + c.tenant + "/start", null)));
        strong.autoCsrf(true);

        String intent = linkIntent(strong, c);
        MvcResult anonymous = browser().perform(MockMvcRequestBuilders.get("/saml2/sp/" + c.tenant + "/login").param("link", intent));
        assertEquals(401, status(anonymous), content(anonymous));
        MvcResult otherSession = weak.perform(MockMvcRequestBuilders.get("/saml2/sp/" + c.tenant + "/login").param("link", intent));
        assertEquals(403, status(otherSession), content(otherSession));
        assertEquals("LINK_SESSION_MISMATCH", json(otherSession).get("code").asText());
        MvcResult used = strong.perform(MockMvcRequestBuilders.get("/saml2/sp/" + c.tenant + "/login").param("link", intent));
        assertEquals(400, status(used), content(used));
        assertEquals("LINK_INTENT_INVALID", json(used).get("code").asText());

        Cfg other = save(tenant("link2"));
        MvcResult wrongTenant = strong.perform(MockMvcRequestBuilders.get("/saml2/sp/" + other.tenant + "/login")
                .param("link", linkIntent(strong, c)));
        assertEquals(400, status(wrongTenant), content(wrongTenant));
        assertEquals("LINK_PROVIDER_MISMATCH", json(wrongTenant).get("code").asText());

        Started foreign = startLink(strong, c, linkIntent(strong, c));
        String forStrong = IDP.response(spec(c, foreign.requestId(), subject, corporate));
        failed(acs(browser(), c, forStrong, null), "SAML_FAILED");
        assertEquals(0, links(u));

        Started s = startLink(strong, c, linkIntent(strong, c));
        TestSamlIdp.Spec badDomain = spec(c, s.requestId(), subject, "x-" + SEQ.incrementAndGet() + "@elsewhere.example.org");
        failed(acs(strong, c, IDP.response(badDomain), null), "DOMAIN_NOT_ALLOWED");
        assertEquals(0, links(u));

        int before = transport.to(u.getEmail()).size();
        Started ok = startLink(strong, c, linkIntent(strong, c));
        String token = strong.cookie("access_token");
        MvcResult linked = acs(strong, c, IDP.response(spec(c, ok.requestId(), subject, corporate).attribute("groups", "staff")), null);
        assertEquals(302, status(linked), content(linked));
        assertEquals(FRONT + "/profile/security?linked=" + enc("saml:" + c.tenant), location(linked));
        assertEquals(token, strong.cookie("access_token"));
        assertEquals(1, links(u));
        assertTrue(auditReasons("saml.sp.link", c.tenant).contains("success"), auditReasons("saml.sp.link", c.tenant).toString());
        assertTrue(transport.to(u.getEmail()).subList(before, transport.to(u.getEmail()).size()).stream()
                .anyMatch(m -> "account_linked".equals(m.vars().get("event"))));

        MvcResult again = linkStart(strong, c);
        assertEquals(409, status(again), content(again));
        assertEquals("IDENTITY_ALREADY_LINKED", json(again).get("code").asText());

        Browser idpLogin = browser();
        Started l = start(idpLogin, c, "/m");
        MvcResult r = acs(idpLogin, c, IDP.response(spec(c, l.requestId(), subject, corporate).attribute("groups", "staff")), null);
        assertTrue(location(r).startsWith(FRONT + "/login#mfaToken="), location(r));
        assertTrue(location(r).contains("methods=totp"), location(r));
        assertFalse(idpLogin.cookies().containsKey("access_token"));
        UserEntity after = userRepository.findById(u.getId()).orElseThrow();
        assertFalse(after.getRoles().stream().anyMatch(x -> "SAML_SP_LINKED".equals(x.getName())), after.getRoles().toString());

        UserEntity second = user(uniqueEmail("link-second"));
        String secondSecret = enrollTotp(loggedIn(second.getEmail())).secret();
        Browser sb = fullyLoggedIn(second.getEmail(), secondSecret);
        Started taken = startLink(sb, c, linkIntent(sb, c));
        failed(acs(sb, c, IDP.response(spec(c, taken.requestId(), subject, corporate)), null), "IDENTITY_ALREADY_LINKED");
        assertEquals(0, links(second));

        Started revoked = startLink(sb, c, linkIntent(sb, c));
        Browser secondOther = fullyLoggedIn(second.getEmail(), secondSecret);
        assertEquals(204, status(secondOther.post(BASE + "/sessions/revoke-others", null)));
        failed(acs(sb, c, IDP.response(spec(c, revoked.requestId(), "lnk-" + SEQ.incrementAndGet(), corporate)), null), "SESSION_REQUIRED");
        assertEquals(0, links(second));

        UserEntity admin = createUser(uniqueEmail("link-admin"), passwordService.hash(PASSWORD), role("SAML_SP_LINK_ADMIN", "SSO_ADMIN_USERS"));
        MvcResult protectedTarget = linkStart(loggedIn(admin.getEmail()), c);
        assertEquals(403, status(protectedTarget), content(protectedTarget));
        assertEquals("LINK_NOT_ALLOWED", json(protectedTarget).get("code").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-680 F-A12-AUTH-1 an account created by JIT in a non-authoritative domain stops answering to the IdP once its mailbox owner verifies the address another way")
    void jitUnverifiedThenClaimed() {
        role("SAML_SP_MEMBER", "profile:read");
        Cfg c = tenant("prehijack");
        c.authoritative = List.of();
        c.defaultRole = "SAML_SP_MEMBER";
        save(c);
        String subject = "pre-" + SEQ.incrementAndGet();
        String email = c.email("pre");
        Browser idp = browser();
        loggedIn(signIn(idp, c, subject, email, "/p"), idp, "/p");
        UserEntity u = userRepository.findByEmail(email).orElseThrow();
        assertFalse(u.isEmailVerified());
        mongo.updateFirst(new Query(Criteria.where("_id").is(u.getId())), new org.springframework.data.mongodb.core.query.Update()
                .set("emailVerified", true), UserEntity.class);
        Browser again = browser();
        failed(signIn(again, c, subject, email, "/p"), "LINK_REQUIRES_LOGIN");
        assertFalse(again.cookies().containsKey("access_token"));
        assertTrue(auditReasons("saml.sp.login", c.tenant).contains("email_verified_elsewhere"), auditReasons("saml.sp.login", c.tenant).toString());
    }

    @Test
    @DisplayName("SSO-T-auth-681 F-A12-AUTH-3 a linked account that later holds an administrative authority no longer signs in through the tenant IdP")
    void linkedAccountPromotedToAdmin() {
        role("SAML_SP_MEMBER", "profile:read");
        Cfg c = tenant("promoted");
        c.defaultRole = "SAML_SP_MEMBER";
        save(c);
        String subject = "adm-" + SEQ.incrementAndGet();
        String email = c.email("adm");
        Browser first = browser();
        loggedIn(signIn(first, c, subject, email, "/p"), first, "/p");
        UserEntity u = userRepository.findByEmail(email).orElseThrow();
        u.getRoles().add(role("SAML_SP_PROMOTED", "SSO_ADMIN_USERS"));
        userRepository.save(u);
        Browser again = browser();
        failed(signIn(again, c, subject, email, "/p"), "LINK_REQUIRES_LOGIN");
        assertFalse(again.cookies().containsKey("access_token"));
        assertTrue(auditReasons("saml.sp.login", c.tenant).contains("target_protected"), auditReasons("saml.sp.login", c.tenant).toString());
    }

    @Test
    @DisplayName("SSO-T-auth-676 MongoSamlRequestStore: single use, expired ids refused, duplicates refused")
    void requestStore() {
        MongoSamlRequestStore store = new MongoSamlRequestStore(mongo, clock);
        String id = "t|_" + Bytes.randomToken(16);
        store.save(id, clock.instant().plusSeconds(60));
        assertTrue(store.consume(id));
        assertFalse(store.consume(id));
        String old = "t|_" + Bytes.randomToken(16);
        store.save(old, clock.instant().minusSeconds(1));
        assertFalse(store.consume(old));
        String dup = "t|_" + Bytes.randomToken(16);
        store.save(dup, clock.instant().plusSeconds(60));
        assertEquals("SAML_REQUEST_EXISTS", org.junit.jupiter.api.Assertions.assertThrows(com.naqqa.sso.SsoException.class,
                () -> store.save(dup, clock.instant().plusSeconds(60))).code());
    }
}
