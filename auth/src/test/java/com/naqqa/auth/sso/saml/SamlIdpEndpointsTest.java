package com.naqqa.auth.sso.saml;

import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.SsoEnabledTestBase;
import com.naqqa.sso.crypto.Pem;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.saml.SamlBindings;
import com.naqqa.sso.saml.dsig.Dsig;
import com.naqqa.sso.saml.dsig.XmlSignatureVerifier;
import com.naqqa.sso.saml.dsig.XmlSigner;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlIdpEndpointsTest extends SsoEnabledTestBase {

    static final String SP = "https://sp.test/metadata";
    static final String ACS = "https://sp.test/acs";
    static final String SIGNED_SP = "https://signed-sp.test/metadata";
    static final String SIGNED_ACS = "https://signed-sp.test/acs";
    static final String DEST = "http://localhost/saml2/idp/sso";
    static final String SLO = "http://localhost/saml2/idp/slo";
    static final String UNKNOWN_CLASS = "urn:oasis:names:tc:SAML:2.0:ac:classes:X509";

    record Request(String id, String xml) {
    }

    record Parsed(XmlDocument doc, XmlElement root, String raw) {
        String status() {
            return root.requiredChild(Saml.PROTOCOL_NS, "Status").requiredChild(Saml.PROTOCOL_NS, "StatusCode").attribute("Value");
        }

        String subStatus() {
            XmlElement inner = root.requiredChild(Saml.PROTOCOL_NS, "Status").requiredChild(Saml.PROTOCOL_NS, "StatusCode")
                    .optionalChild(Saml.PROTOCOL_NS, "StatusCode");
            return inner == null ? null : inner.attribute("Value");
        }

        XmlElement assertion() {
            return root.optionalChild(Saml.ASSERTION_NS, "Assertion");
        }

        String classRef() {
            return Saml.trimmedText(assertion().requiredChild(Saml.ASSERTION_NS, "AuthnStatement")
                    .requiredChild(Saml.ASSERTION_NS, "AuthnContext").requiredChild(Saml.ASSERTION_NS, "AuthnContextClassRef"));
        }

        String nameId() {
            return Saml.trimmedText(assertion().requiredChild(Saml.ASSERTION_NS, "Subject").requiredChild(Saml.ASSERTION_NS, "NameID"));
        }
    }

    Request authn(String sp, String acs, boolean passive, boolean force, List<String> contexts, Instant issued) {
        String id = Saml.newId();
        StringBuilder sb = new StringBuilder();
        sb.append("<samlp:AuthnRequest xmlns:samlp=\"").append(Saml.PROTOCOL_NS).append("\" xmlns:saml=\"").append(Saml.ASSERTION_NS)
                .append("\" ID=\"").append(id).append("\" Version=\"2.0\" IssueInstant=\"").append(Saml.time(issued))
                .append("\" Destination=\"").append(DEST).append("\"");
        if (acs != null) {
            sb.append(" AssertionConsumerServiceURL=\"").append(acs).append("\"");
        }
        if (passive) {
            sb.append(" IsPassive=\"true\"");
        }
        if (force) {
            sb.append(" ForceAuthn=\"true\"");
        }
        sb.append("><saml:Issuer>").append(sp).append("</saml:Issuer>");
        if (contexts != null && !contexts.isEmpty()) {
            sb.append("<samlp:RequestedAuthnContext Comparison=\"exact\">");
            for (String c : contexts) {
                sb.append("<saml:AuthnContextClassRef>").append(c).append("</saml:AuthnContextClassRef>");
            }
            sb.append("</samlp:RequestedAuthnContext>");
        }
        sb.append("</samlp:AuthnRequest>");
        return new Request(id, sb.toString());
    }

    Request authn(boolean passive, List<String> contexts) {
        return authn(SP, null, passive, false, contexts, clock.instant());
    }

    static String redirectQuery(String xml, String relayState) {
        String q = "SAMLRequest=" + SamlBindings.urlEncode(Base64.getEncoder().encodeToString(SamlBindings.deflate(xml.getBytes(StandardCharsets.UTF_8))));
        if (relayState != null) {
            q += "&RelayState=" + SamlBindings.urlEncode(relayState);
        }
        return q;
    }

    static MvcResult getUri(Browser b, String pathAndQuery) {
        return b.perform(MockMvcRequestBuilders.get(URI.create(pathAndQuery)));
    }

    static MvcResult sso(Browser b, Request r, String relayState) {
        return getUri(b, "/saml2/idp/sso?" + redirectQuery(r.xml(), relayState));
    }

    static Parsed parseForm(MvcResult result) {
        String html = content(result);
        Matcher m = Pattern.compile("name=\"SAMLResponse\" value=\"([^\"]+)\"").matcher(html);
        assertTrue(m.find(), html);
        byte[] xml = Base64.getDecoder().decode(m.group(1).replace("&#x2B;", "+").replace("&#43;", "+").replace("&#x3D;", "=").replace("&#61;", "="));
        XmlDocument doc = new XmlParser().parse(xml);
        return new Parsed(doc, doc.root(), new String(xml, StandardCharsets.UTF_8));
    }

    X509Certificate idpCertificate() throws Exception {
        MvcResult r = browser().get("/saml2/idp/metadata");
        XmlDocument doc = new XmlParser().parse(content(r));
        XmlElement cert = doc.elements().stream().filter(e -> e.is(Dsig.NS, "X509Certificate")).findFirst().orElseThrow();
        byte[] der = Base64.getMimeDecoder().decode(Saml.trimmedText(cert));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    static PrivateKey spKey() throws Exception {
        return Pem.parsePrivateKey(new String(new ClassPathResource("saml/sp-signing.key.pem").getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
    }

    static X509Certificate spCert() throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ClassPathResource("saml/sp-signing.crt.pem").getInputStream());
    }

    static String signedQuery(String location, String xml, String relayState) throws Exception {
        String url = SamlBindings.encodeRedirect(location, "SAMLRequest", xml.getBytes(StandardCharsets.UTF_8), relayState,
                new XmlSigner(spKey(), spCert()));
        return url.substring(url.indexOf('?') + 1);
    }

    Browser passwordSession(String tag) {
        String email = uniqueEmail(tag);
        user(email);
        return loggedIn(email);
    }

    @Test
    @DisplayName("SSO-T-auth-300 SAML metadata is served with the IdP signing certificate and endpoints")
    void metadata() throws Exception {
        MvcResult r = browser().get("/saml2/idp/metadata");
        assertEquals(200, status(r));
        assertTrue(r.getResponse().getContentType().startsWith("application/samlmetadata+xml"));
        String xml = content(r);
        assertTrue(xml.contains("entityID=\"http://localhost/saml2/idp/metadata\""));
        assertTrue(xml.contains("Location=\"" + DEST + "\""));
        X509Certificate cert = idpCertificate();
        cert.checkValidity();
        assertEquals("RSA", cert.getPublicKey().getAlgorithm());
    }

    @Test
    @DisplayName("SSO-T-auth-301 authenticated session receives a signed Response posted only to the registered ACS")
    void authenticatedSso() throws Exception {
        Browser b = passwordSession("saml-ok");
        Request req = authn(false, List.of());
        MvcResult r = sso(b, req, "rs-1");
        assertEquals(200, status(r), content(r));
        assertTrue(content(r).contains("action=\"" + ACS + "\""));
        assertTrue(content(r).contains("name=\"RelayState\" value=\"rs-1\""));
        Parsed p = parseForm(r);
        assertEquals(Saml.STATUS_SUCCESS, p.status());
        assertEquals(req.id(), p.root().attribute("InResponseTo"));
        assertEquals(ACS, p.root().attribute("Destination"));
        assertEquals(Saml.AC_PASSWORD_PROTECTED, p.classRef());
        assertTrue(p.nameId().endsWith("@example.test"));
        new XmlSignatureVerifier(List.of(idpCertificate())).verify(p.doc(), p.root());
    }

    @Test
    @DisplayName("SSO-T-auth-302 Response signature does not verify under a foreign certificate")
    void signatureBoundToIdpKey() throws Exception {
        Browser b = passwordSession("saml-sig");
        Parsed p = parseForm(sso(b, authn(false, List.of()), null));
        assertThrows(RuntimeException.class, () -> new XmlSignatureVerifier(List.of(spCert())).verify(p.doc(), p.root()));
    }

    @Test
    @DisplayName("SSO-T-auth-303 unauthenticated non-passive request redirects to login with a pending ticket and no assertion")
    void unauthenticatedRedirects() {
        Browser b = browser();
        MvcResult r = sso(b, authn(false, List.of()), null);
        assertEquals(302, status(r));
        String location = r.getResponse().getHeader("Location");
        assertTrue(location.startsWith("http://localhost:4200/login?saml="), location);
        assertFalse(location.contains("stepUp"));
        assertNotNull(b.cookie(SamlIdpController.PENDING_COOKIE));
        assertFalse(content(r).contains("SAMLResponse"));
    }

    @Test
    @DisplayName("SSO-T-auth-304 F-S4 IsPassive without a session answers NoPassive and never redirects to login")
    void passiveWithoutSession() {
        MvcResult r = sso(browser(), authn(true, List.of()), null);
        assertEquals(200, status(r));
        Parsed p = parseForm(r);
        assertEquals(Saml.STATUS_RESPONDER, p.status());
        assertEquals(Saml.STATUS_NO_PASSIVE, p.subStatus());
        assertEquals(null, p.assertion());
    }

    @Test
    @DisplayName("SSO-T-auth-305 F-S4 RequestedAuthnContext MFA with a password-only session and IsPassive answers NoPassive")
    void passiveMfaWithPasswordSession() {
        Browser b = passwordSession("saml-pmfa");
        Parsed p = parseForm(sso(b, authn(true, List.of(Saml.AC_MFA)), null));
        assertEquals(Saml.STATUS_NO_PASSIVE, p.subStatus());
        assertEquals(null, p.assertion());
    }

    @Test
    @DisplayName("SSO-T-auth-306 F-S4 RequestedAuthnContext MFA with a password-only session redirects to step-up, no downgrade to PPT")
    void interactiveMfaStepUp() {
        Browser b = passwordSession("saml-imfa");
        MvcResult r = sso(b, authn(false, List.of(Saml.AC_MFA)), null);
        assertEquals(302, status(r));
        assertTrue(r.getResponse().getHeader("Location").contains("stepUp=true"));
    }

    @Test
    @DisplayName("SSO-T-auth-307 RequestedAuthnContext MFA with an MFA session asserts exactly the REFEDS MFA class")
    void mfaSessionAssertsMfa() {
        String email = uniqueEmail("saml-mfa");
        user(email);
        Browser setup = loggedIn(email);
        Totp t = enrollTotp(setup);
        Browser b = fullyLoggedIn(email, t.secret());
        Parsed p = parseForm(sso(b, authn(false, List.of(Saml.AC_MFA)), null));
        assertEquals(Saml.STATUS_SUCCESS, p.status());
        assertEquals(Saml.AC_MFA, p.classRef());
    }

    @Test
    @DisplayName("SSO-T-auth-308 PPT requested with an MFA session asserts PPT (exact comparison honoured)")
    void pptRequestedExact() {
        String email = uniqueEmail("saml-ppt");
        user(email);
        Totp t = enrollTotp(loggedIn(email));
        Browser b = fullyLoggedIn(email, t.secret());
        Parsed p = parseForm(sso(b, authn(false, List.of(Saml.AC_PASSWORD_PROTECTED)), null));
        assertEquals(Saml.AC_PASSWORD_PROTECTED, p.classRef());
    }

    @Test
    @DisplayName("SSO-T-auth-309 F-S4 unknown requested class only answers NoAuthnContext without an assertion")
    void unknownClass() {
        Browser b = passwordSession("saml-unk");
        Parsed p = parseForm(sso(b, authn(false, List.of(UNKNOWN_CLASS)), null));
        assertEquals(SamlIdpController.STATUS_NO_AUTHN_CONTEXT, p.subStatus());
        assertEquals(null, p.assertion());
    }

    @Test
    @DisplayName("SSO-T-auth-310 first satisfiable class in the request order is asserted when unknown classes are mixed in")
    void mixedClasses() {
        Browser b = passwordSession("saml-mix");
        Parsed p = parseForm(sso(b, authn(false, List.of(UNKNOWN_CLASS, Saml.AC_MFA, Saml.AC_PASSWORD_PROTECTED)), null));
        assertEquals(Saml.AC_PASSWORD_PROTECTED, p.classRef());
    }

    @Test
    @DisplayName("SSO-T-auth-311 F-S4 ForceAuthn with an older authentication is not silently satisfied")
    void forceAuthnOldSession() {
        Browser b = passwordSession("saml-force");
        clock.advance(Duration.ofSeconds(20));
        MvcResult r = sso(b, authn(SP, null, false, true, List.of(), clock.instant()), null);
        assertEquals(302, status(r));
        Parsed p = parseForm(sso(b, authn(SP, null, true, true, List.of(), clock.instant()), null));
        assertEquals(Saml.STATUS_NO_PASSIVE, p.subStatus());
    }

    @Test
    @DisplayName("SSO-T-auth-312 unknown SP is rejected with JSON 400 and no form is produced")
    void unknownSp() {
        Browser b = passwordSession("saml-unksp");
        MvcResult r = sso(b, authn("https://evil.test/metadata", null, false, false, List.of(), clock.instant()), null);
        assertEquals(400, status(r));
        assertTrue(content(r).contains("\"code\""));
        assertFalse(content(r).contains("<form"));
    }

    @Test
    @DisplayName("SSO-T-auth-313 ACS URL not registered for the SP is rejected before any assertion is built")
    void unregisteredAcs() {
        Browser b = passwordSession("saml-acs");
        MvcResult r = sso(b, authn(SP, "https://evil.test/acs", false, false, List.of(), clock.instant()), null);
        assertEquals(400, status(r));
        assertTrue(content(r).contains("SAML_ACS_MISMATCH"));
    }

    @Test
    @DisplayName("SSO-T-auth-314 replayed AuthnRequest ID is rejected")
    void replay() {
        Browser b = passwordSession("saml-replay");
        Request req = authn(false, List.of());
        assertEquals(200, status(sso(b, req, null)));
        MvcResult again = sso(b, req, null);
        assertEquals(400, status(again));
        assertTrue(content(again).contains("SAML_REPLAY"));
    }

    @Test
    @DisplayName("SSO-T-auth-315 pending ticket resumes after login in the same browser and is single use")
    void continueAfterLogin() {
        String email = uniqueEmail("saml-cont");
        user(email);
        Browser b = browser();
        Request req = authn(false, List.of());
        MvcResult r = sso(b, req, "state-9");
        String ticket = r.getResponse().getHeader("Location").replaceAll(".*saml=([^&]+).*", "$1");
        assertEquals(200, status(login(b, email, PASSWORD)));
        MvcResult resumed = b.get("/saml2/idp/continue?ticket=" + ticket);
        assertEquals(200, status(resumed), content(resumed));
        Parsed p = parseForm(resumed);
        assertEquals(req.id(), p.root().attribute("InResponseTo"));
        assertEquals(email, p.nameId());
        assertTrue(content(resumed).contains("value=\"state-9\""));
        assertEquals(400, status(b.get("/saml2/idp/continue?ticket=" + ticket)));
    }

    @Test
    @DisplayName("SSO-T-auth-316 pending ticket cannot be resumed from another browser or by another user")
    void continueOtherBrowser() {
        Browser starter = browser();
        MvcResult r = sso(starter, authn(false, List.of()), null);
        String ticket = r.getResponse().getHeader("Location").replaceAll(".*saml=([^&]+).*", "$1");
        Browser attacker = passwordSession("saml-thief");
        MvcResult stolen = attacker.get("/saml2/idp/continue?ticket=" + ticket);
        assertEquals(403, status(stolen));
        assertFalse(content(stolen).contains("SAMLResponse"));
    }

    @Test
    @DisplayName("SSO-T-auth-317 step-up ticket is bound to the user that started it")
    void stepUpTicketBoundToUser() {
        Browser b = passwordSession("saml-bound");
        MvcResult r = sso(b, authn(false, List.of(Saml.AC_MFA)), null);
        String ticket = r.getResponse().getHeader("Location").replaceAll(".*saml=([^&]+).*", "$1");
        String other = uniqueEmail("saml-other");
        user(other);
        Browser same = b;
        assertEquals(200, status(login(same, other, PASSWORD)));
        assertEquals(403, status(same.get("/saml2/idp/continue?ticket=" + ticket)));
    }

    @Test
    @DisplayName("SSO-T-auth-318 continue without authentication is 401")
    void continueUnauthenticated() {
        Browser b = browser();
        MvcResult r = sso(b, authn(false, List.of()), null);
        String ticket = r.getResponse().getHeader("Location").replaceAll(".*saml=([^&]+).*", "$1");
        assertEquals(401, status(b.get("/saml2/idp/continue?ticket=" + ticket)));
    }

    @Test
    @DisplayName("SSO-T-auth-319 form response carries a CSP that only allows the auto-submit hash and the ACS origin")
    void cspOnForm() {
        Browser b = passwordSession("saml-csp");
        MvcResult r = sso(b, authn(false, List.of()), null);
        String csp = r.getResponse().getHeader("Content-Security-Policy");
        assertNotNull(csp);
        assertTrue(csp.contains("form-action https://sp.test;"), csp);
        assertTrue(csp.contains(SamlIdpController.SCRIPT_HASH), csp);
        assertTrue(csp.contains("default-src 'none'"));
        assertFalse(csp.contains("unsafe-inline"));
        assertEquals("no-store", r.getResponse().getHeader("Cache-Control"));
    }

    @Test
    @DisplayName("SSO-T-auth-320 POST binding is accepted for an unsigned SP")
    void postBinding() {
        Browser b = passwordSession("saml-post");
        Request req = authn(false, List.of());
        MvcResult r = b.perform(MockMvcRequestBuilders.post("/saml2/idp/sso")
                .param("SAMLRequest", Base64.getEncoder().encodeToString(req.xml().getBytes(StandardCharsets.UTF_8))));
        assertEquals(200, status(r), content(r));
        assertEquals(req.id(), parseForm(r).root().attribute("InResponseTo"));
    }

    @Test
    @DisplayName("SSO-T-auth-321 SP requiring signed requests rejects an unsigned AuthnRequest")
    void unsignedRejected() {
        Browser b = passwordSession("saml-unsigned");
        MvcResult r = sso(b, authn(SIGNED_SP, null, false, false, List.of(), clock.instant()), null);
        assertEquals(400, status(r));
        assertTrue(content(r).contains("SAML_SIGNATURE_REQUIRED"));
    }

    @Test
    @DisplayName("SSO-T-auth-322 signed AuthnRequest from the SP key is accepted, tampered signature rejected")
    void signedAccepted() throws Exception {
        Browser b = passwordSession("saml-signed");
        Request req = authn(SIGNED_SP, null, false, false, List.of(), clock.instant());
        String query = signedQuery(DEST, req.xml(), "rs");
        MvcResult ok = getUri(b, "/saml2/idp/sso?" + query);
        assertEquals(200, status(ok), content(ok));
        assertTrue(content(ok).contains("action=\"" + SIGNED_ACS + "\""));
        Request other = authn(SIGNED_SP, null, false, false, List.of(), clock.instant());
        String tampered = signedQuery(DEST, other.xml(), "rs").replace("RelayState=rs", "RelayState=rx");
        assertEquals(400, status(getUri(b, "/saml2/idp/sso?" + tampered)));
    }

    @Test
    @DisplayName("SSO-T-auth-323 signed SLO LogoutRequest revokes the user's sessions and replies to the SP SLO URL")
    void singleLogout() throws Exception {
        String email = uniqueEmail("saml-slo");
        user(email);
        Browser b = loggedIn(email);
        assertEquals(200, status(b.get(BASE + "/sessions")));
        assertEquals(200, status(getUri(b, "/saml2/idp/sso?" + signedQuery(DEST, authn(SIGNED_SP, null, false, false, List.of(), clock.instant()).xml(), null))));
        String id = Saml.newId();
        String xml = "<samlp:LogoutRequest xmlns:samlp=\"" + Saml.PROTOCOL_NS + "\" xmlns:saml=\"" + Saml.ASSERTION_NS + "\" ID=\"" + id
                + "\" Version=\"2.0\" IssueInstant=\"" + Saml.time(clock.instant()) + "\" Destination=\"" + SLO + "\"><saml:Issuer>"
                + SIGNED_SP + "</saml:Issuer><saml:NameID Format=\"" + Saml.NAMEID_EMAIL + "\">" + email + "</saml:NameID></samlp:LogoutRequest>";
        MvcResult r = getUri(browser(), "/saml2/idp/slo?" + signedQuery(SLO, xml, null));
        assertEquals(200, status(r), content(r));
        assertTrue(content(r).contains("action=\"https://signed-sp.test/slo\""));
        Parsed p = parseForm(r);
        assertEquals(id, p.root().attribute("InResponseTo"));
        assertEquals(Saml.STATUS_SUCCESS, p.status());
        assertEquals(401, status(b.get(BASE + "/sessions")));
    }

    String logoutRequest(String email, String sessionIndex) {
        return "<samlp:LogoutRequest xmlns:samlp=\"" + Saml.PROTOCOL_NS + "\" xmlns:saml=\"" + Saml.ASSERTION_NS + "\" ID=\"" + Saml.newId()
                + "\" Version=\"2.0\" IssueInstant=\"" + Saml.time(clock.instant()) + "\" Destination=\"" + SLO + "\"><saml:Issuer>"
                + SIGNED_SP + "</saml:Issuer><saml:NameID Format=\"" + Saml.NAMEID_EMAIL + "\">" + email + "</saml:NameID>"
                + (sessionIndex == null ? "" : "<samlp:SessionIndex>" + sessionIndex + "</samlp:SessionIndex>") + "</samlp:LogoutRequest>";
    }

    String currentSession(Browser b) {
        for (com.fasterxml.jackson.databind.JsonNode s : json(b.get(BASE + "/sessions"))) {
            if (s.get("current").asBoolean()) {
                return s.get("id").asText();
            }
        }
        throw new AssertionError("no current session");
    }

    @Test
    @DisplayName("SSO-T-auth-627 F-A8-AUTH-3 a signed SLO request ends only sessions that signed in to that SP: other users and other sessions of the user stay")
    void sloEndsOnlyParticipatingSessions() throws Exception {
        String email = uniqueEmail("saml-slo-scope");
        user(email);
        Browser viaSp = loggedIn(email);
        Browser elsewhere = new Browser(mvc, viaSp.ip()).cookie("sso_device", viaSp.cookie("sso_device"));
        assertEquals("ok", json(login(elsewhere, email, PASSWORD)).get("status").asText());
        assertEquals(200, status(getUri(viaSp, "/saml2/idp/sso?" + signedQuery(DEST, authn(SIGNED_SP, null, false, false, List.of(), clock.instant()).xml(), null))));
        String victim = uniqueEmail("saml-slo-victim");
        user(victim);
        Browser v = loggedIn(victim);
        MvcResult foreign = getUri(browser(), "/saml2/idp/slo?" + signedQuery(SLO, logoutRequest(victim, null), null));
        assertEquals(200, status(foreign), content(foreign));
        assertEquals(200, status(v.get(BASE + "/sessions")));
        MvcResult indexed = getUri(browser(), "/saml2/idp/slo?" + signedQuery(SLO, logoutRequest(email, currentSession(elsewhere)), null));
        assertEquals(200, status(indexed), content(indexed));
        assertEquals(200, status(elsewhere.get(BASE + "/sessions")));
        assertEquals(200, status(viaSp.get(BASE + "/sessions")));
        MvcResult all = getUri(browser(), "/saml2/idp/slo?" + signedQuery(SLO, logoutRequest(email, null), null));
        assertEquals(200, status(all), content(all));
        assertEquals(401, status(viaSp.get(BASE + "/sessions")));
        assertEquals(200, status(elsewhere.get(BASE + "/sessions")));
    }

    @Test
    @DisplayName("SSO-T-auth-324 unsigned SLO LogoutRequest is rejected and sessions stay active")
    void unsignedSloRejected() {
        String email = uniqueEmail("saml-slo2");
        user(email);
        Browser b = loggedIn(email);
        String xml = "<samlp:LogoutRequest xmlns:samlp=\"" + Saml.PROTOCOL_NS + "\" xmlns:saml=\"" + Saml.ASSERTION_NS + "\" ID=\"" + Saml.newId()
                + "\" Version=\"2.0\" IssueInstant=\"" + Saml.time(clock.instant()) + "\" Destination=\"" + SLO + "\"><saml:Issuer>"
                + SIGNED_SP + "</saml:Issuer><saml:NameID Format=\"" + Saml.NAMEID_EMAIL + "\">" + email + "</saml:NameID></samlp:LogoutRequest>";
        MvcResult r = getUri(browser(), "/saml2/idp/slo?" + redirectQuery(xml, null));
        assertEquals(400, status(r));
        assertEquals(200, status(b.get(BASE + "/sessions")));
    }

    @Test
    @DisplayName("SSO-T-auth-325 revoked session cannot obtain an assertion with its leftover access token")
    void revokedSessionNoAssertion() {
        Browser b = passwordSession("saml-revoked");
        String sid = json(b.get(BASE + "/sessions")).get(0).get("id").asText();
        assertEquals(204, status(b.delete(BASE + "/sessions/" + sid)));
        MvcResult r = sso(b, authn(true, List.of()), null);
        assertEquals(Saml.STATUS_NO_PASSIVE, parseForm(r).subStatus());
    }

    @Test
    @DisplayName("SSO-T-auth-326 malformed SAMLRequest is rejected with JSON 400")
    void malformed() {
        MvcResult r = getUri(browser(), "/saml2/idp/sso?SAMLRequest=not-base64%21%21");
        assertEquals(400, status(r));
        assertNotEquals(-1, content(r).indexOf("\"code\""));
    }
}
