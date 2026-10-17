package com.naqqa.sso.saml;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.idp.AuthnRequestInfo;
import com.naqqa.sso.saml.sp.OutboundAuthnRequest;
import com.naqqa.sso.saml.sp.SamlPrincipal;
import com.naqqa.sso.saml.sp.SamlSp;
import com.naqqa.sso.saml.sp.SamlSpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SamlSpCoreRound4Test extends SamlTestBase {
    private static final TestKeys.Cred NEXT = TestKeys.rsa("idp-next", 2048);
    private static final int THREADS = 16;

    private SamlSp trusting(List<X509Certificate> certs) {
        return newSp(new SamlSpConfig(SP_ENTITY, SP_ACS, IDP_ENTITY, IDP_SSO, certs, null, null, null,
                true, false, false, false, null, null, null));
    }

    private static String nameIdContent(String xml, String content) {
        return xml.replaceFirst("(<saml:NameID[^>]*>)[^<]*(</saml:NameID>)", "$1" + content + "$2");
    }

    private static Map<String, Integer> race(Callable<String> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        return task.call();
                    } catch (SsoException e) {
                        return e.code();
                    }
                }));
            }
            go.countDown();
            Map<String, Integer> out = new ConcurrentHashMap<>();
            for (Future<String> f : futures) {
                out.merge(f.get(60, TimeUnit.SECONDS), 1, Integer::sum);
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("SSO-T-saml-136 IdP signing key rollover: any configured certificate verifies, KeyInfo only orders the candidates, an untrusted key behind a trusted KeyInfo certificate is rejected")
    void signingKeyRollover() {
        Flow f = flow();
        String byNext = resignAssertion(f.xml(), a -> a, NEXT);
        assertCode("DSIG_SIGNATURE_INVALID", () -> trusting(List.of(TestKeys.IDP.cert())).verifyResponse(encode(byNext), f.requestId()));
        assertEquals("alice@x.com", trusting(List.of(TestKeys.IDP.cert(), NEXT.cert())).verifyResponse(encode(byNext), f.requestId()).nameId());
        Flow g = flow();
        String hintMismatch = resignAssertion(g.xml(), a -> a, new TestKeys.Cred(TestKeys.IDP.key(), NEXT.cert()));
        assertEquals("alice@x.com", trusting(List.of(NEXT.cert(), TestKeys.IDP.cert())).verifyResponse(encode(hintMismatch), g.requestId()).nameId());
        Flow h = flow();
        String forged = resignAssertion(h.xml(), a -> a.replace("alice@x.com", "admin@x.com"),
                new TestKeys.Cred(TestKeys.ATTACKER.key(), TestKeys.IDP.cert()));
        assertCode("DSIG_SIGNATURE_INVALID", () -> trusting(List.of(TestKeys.IDP.cert(), NEXT.cert())).verifyResponse(encode(forged), h.requestId()));
    }

    @Test
    @DisplayName("SSO-T-saml-137 NameID must be plain non-empty text; Format is reported as signed")
    void nameIdShape() {
        Flow f = flow();
        assertCode("SAML_MALFORMED", () -> sp.verifyResponse(encode(resignAssertion(f.xml(), a -> nameIdContent(a, "  \n "))), f.requestId()));
        Flow g = flow();
        assertCode("SAML_MALFORMED", () -> sp.verifyResponse(encode(resignAssertion(g.xml(),
                a -> nameIdContent(a, "<saml:Extra>alice@x.com</saml:Extra>"))), g.requestId()));
        Flow h = flow();
        SamlPrincipal p = sp.verifyResponse(encode(resignAssertion(h.xml(), a -> nameIdContent(a, " bob@x.com "))), h.requestId());
        assertEquals("bob@x.com", p.nameId());
        assertEquals(Saml.NAMEID_EMAIL, p.nameIdFormat());
    }

    @Test
    @DisplayName("SSO-T-saml-138 AuthnStatement is required and an expired SessionNotOnOrAfter is rejected")
    void authnStatement() {
        Flow f = flow();
        assertCode("SAML_NO_AUTHN_STATEMENT", () -> sp.verifyResponse(encode(resignAssertion(f.xml(),
                a -> a.replaceAll("<saml:AuthnStatement.*</saml:AuthnStatement>", ""))), f.requestId()));
        Flow g = flow();
        String expired = Saml.time(T0.minus(Duration.ofMinutes(4)));
        assertCode("SAML_EXPIRED", () -> sp.verifyResponse(encode(resignAssertion(g.xml(),
                a -> a.replace("<saml:AuthnStatement ", "<saml:AuthnStatement SessionNotOnOrAfter=\"" + expired + "\" "))), g.requestId()));
        Flow h = flow();
        String withinSkew = Saml.time(T0.minus(Duration.ofMinutes(2)));
        assertNotNull(sp.verifyResponse(encode(resignAssertion(h.xml(),
                a -> a.replace("<saml:AuthnStatement ", "<saml:AuthnStatement SessionNotOnOrAfter=\"" + withinSkew + "\" "))), h.requestId()));
    }

    @Test
    @DisplayName("SSO-T-saml-139 one response posted by many threads at once yields one principal; the rest are replays")
    void parallelReplay() throws Exception {
        Flow f = flow();
        Map<String, Integer> out = race(() -> sp.verifyResponse(f.message().value(), f.requestId()).nameId());
        assertEquals(Map.of("alice@x.com", 1, "SAML_REPLAY", THREADS - 1), out);
    }

    @Test
    @DisplayName("SSO-T-saml-140 many distinct responses for one AuthnRequest posted at once: the request is consumed by exactly one")
    void parallelRequestConsume() throws Exception {
        OutboundAuthnRequest out = sp.createAuthnRequest(null);
        AuthnRequestInfo info = idp.parseAuthnRequestRedirect(query(out.redirectUrl()));
        List<String> responses = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            responses.add(idp.issueResponse(info, subject()).value());
        }
        AtomicInteger n = new AtomicInteger();
        Map<String, Integer> result = race(() -> sp.verifyResponse(responses.get(n.getAndIncrement()), out.id()).nameId());
        assertEquals(Map.of("alice@x.com", 1, "SAML_UNKNOWN_REQUEST", THREADS - 1), result);
    }

    @Test
    @DisplayName("SSO-T-saml-141 bearer SubjectConfirmation: a wrong confirmation before a valid one is skipped, only wrong ones report the specific failure")
    void bearerConfirmations() {
        Flow f = flow();
        String xml = f.xml();
        int s = xml.indexOf("<saml:SubjectConfirmation ");
        int e = xml.indexOf("</saml:SubjectConfirmation>") + "</saml:SubjectConfirmation>".length();
        String sc = xml.substring(s, e);
        String wrong = sc.replace("Recipient=\"" + SP_ACS + "\"", "Recipient=\"" + SP_ACS2 + "\"");
        assertEquals("alice@x.com", sp.verifyResponse(encode(resignAssertion(xml, a -> a.replace(sc, wrong + sc))), f.requestId()).nameId());
        Flow g = flow();
        String gx = g.xml();
        String gsc = gx.substring(gx.indexOf("<saml:SubjectConfirmation "),
                gx.indexOf("</saml:SubjectConfirmation>") + "</saml:SubjectConfirmation>".length());
        String gWrong = gsc.replace("Recipient=\"" + SP_ACS + "\"", "Recipient=\"" + SP_ACS2 + "\"");
        assertCode("SAML_RECIPIENT", () -> sp.verifyResponse(encode(resignAssertion(gx, a -> a.replace(gsc, gWrong))), g.requestId()));
    }
}
