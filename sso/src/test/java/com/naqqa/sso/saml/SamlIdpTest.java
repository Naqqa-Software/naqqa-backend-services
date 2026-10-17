package com.naqqa.sso.saml;

import com.naqqa.sso.saml.dsig.Dsig;
import com.naqqa.sso.saml.dsig.XmlSignatureVerifier;
import com.naqqa.sso.saml.dsig.XmlSigner;
import com.naqqa.sso.saml.idp.AuthnRequestInfo;
import com.naqqa.sso.saml.idp.LogoutRequestInfo;
import com.naqqa.sso.saml.idp.LogoutResponseInfo;
import com.naqqa.sso.saml.idp.SamlIdp;
import com.naqqa.sso.saml.idp.SamlIdpConfig;
import com.naqqa.sso.saml.idp.SamlSubject;
import com.naqqa.sso.saml.idp.ServiceProvider;
import com.naqqa.sso.saml.xml.XmlDocument;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlParser;
import com.naqqa.sso.saml.xml.XmlSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlIdpTest extends SamlTestBase {
    private final XmlParser parser = new XmlParser();
    private final XmlSigner spSigner = new XmlSigner(TestKeys.SP.key(), TestKeys.SP.cert());

    private String request(String id, Instant issued, String issuer, String extraAttrs, String body) {
        return "<samlp:AuthnRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
                + "xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"" + id + "\" Version=\"2.0\" IssueInstant=\""
                + Saml.time(issued) + "\" Destination=\"" + IDP_SSO + "\"" + extraAttrs + ">"
                + "<saml:Issuer>" + issuer + "</saml:Issuer>" + body + "</samlp:AuthnRequest>";
    }

    private String request(String extraAttrs, String body) {
        return request(Saml.newId(), T0, SP_ENTITY, extraAttrs, body);
    }

    private String redirect(String xml, XmlSigner signer) {
        return query(SamlBindings.encodeRedirect(IDP_SSO, "SAMLRequest", xml.getBytes(StandardCharsets.UTF_8), "rs", signer));
    }

    private AuthnRequestInfo parseRedirect(String xml) {
        return idp.parseAuthnRequestRedirect(redirect(xml, null));
    }

    private XmlElement responseRoot(SamlPostMessage m) {
        return parser.parse(decode(m.value())).root();
    }

    @Test
    @DisplayName("SSO-T-saml-066 IdP metadata lists entity, signing cert and endpoints")
    void metadata() throws Exception {
        XmlElement ed = parser.parse(idp.metadata()).root();
        assertTrue(ed.is(Saml.METADATA_NS, "EntityDescriptor"));
        assertEquals(IDP_ENTITY, ed.attribute("entityID"));
        XmlElement d = ed.requiredChild(Saml.METADATA_NS, "IDPSSODescriptor");
        String cert = d.requiredChild(Saml.METADATA_NS, "KeyDescriptor").requiredChild(Dsig.NS, "KeyInfo")
                .requiredChild(Dsig.NS, "X509Data").requiredChild(Dsig.NS, "X509Certificate").text();
        assertEquals(Dsig.base64(TestKeys.IDP.cert().getEncoded()), cert);
        assertEquals(2, d.childElements(Saml.METADATA_NS, "SingleSignOnService").size());
        assertEquals(IDP_SSO, d.childElements(Saml.METADATA_NS, "SingleSignOnService").get(0).attribute("Location"));
        assertEquals(3, d.childElements(Saml.METADATA_NS, "NameIDFormat").size());
    }

    @Test
    @DisplayName("SSO-T-saml-067 unsigned redirect AuthnRequest accepted when SP does not require signing")
    void unsignedRedirect() {
        AuthnRequestInfo info = parseRedirect(request("", ""));
        assertEquals(SP_ENTITY, info.spEntityId());
        assertEquals(SP_ACS, info.assertionConsumerServiceUrl());
        assertEquals("rs", info.relayState());
        assertEquals(Saml.NAMEID_EMAIL, info.nameIdFormat());
        assertFalse(info.signed());
    }

    @Test
    @DisplayName("SSO-T-saml-068 signed redirect AuthnRequest from SamlSp verified over the query string")
    void signedRedirect() {
        registerSp(true, false, false);
        String url = sp.createAuthnRequest("state").redirectUrl();
        assertTrue(url.contains("SigAlg=") && url.contains("Signature="));
        AuthnRequestInfo info = idp.parseAuthnRequestRedirect(query(url));
        assertTrue(info.signed());
        assertEquals("state", info.relayState());
    }

    @Test
    @DisplayName("SSO-T-saml-069 redirect signature by unknown key rejected")
    void redirectWrongKey() {
        XmlSigner attacker = new XmlSigner(TestKeys.ATTACKER.key(), TestKeys.ATTACKER.cert());
        assertCode("DSIG_SIGNATURE_INVALID", () -> idp.parseAuthnRequestRedirect(redirect(request("", ""), attacker)));
    }

    @Test
    @DisplayName("SSO-T-saml-070 redirect RelayState tamper after signing rejected")
    void redirectRelayTamper() {
        String q = redirect(request("", ""), spSigner);
        assertCode("DSIG_SIGNATURE_INVALID", () -> idp.parseAuthnRequestRedirect(q.replace("RelayState=rs", "RelayState=evil")));
    }

    @Test
    @DisplayName("SSO-T-saml-071 missing signature rejected when SP requires signed requests")
    void signatureRequired() {
        registerSp(true, false, false);
        assertCode("SAML_SIGNATURE_REQUIRED", () -> parseRedirect(request("", "")));
        assertCode("SAML_SIGNATURE_REQUIRED", () -> idp.parseAuthnRequestPost(encode(request("", "")), null));
    }

    @Test
    @DisplayName("SSO-T-saml-072 redirect SigAlg downgrade to rsa-sha1 rejected")
    void redirectSigAlg() {
        String q = redirect(request("", ""), spSigner);
        String downgraded = q.replace(SamlBindings.urlEncode(Dsig.RSA_SHA256),
                SamlBindings.urlEncode("http://www.w3.org/2000/09/xmldsig#rsa-sha1"));
        assertCode("DSIG_ALGORITHM", () -> idp.parseAuthnRequestRedirect(downgraded));
    }

    @Test
    @DisplayName("SSO-T-saml-073 deflate bomb capped at 256 KiB output")
    void deflateBomb() {
        byte[] bomb = new byte[8 * 1024 * 1024];
        java.util.Arrays.fill(bomb, (byte) 'a');
        String q = "SAMLRequest=" + SamlBindings.urlEncode(encodeBytes(SamlBindings.deflate(bomb)));
        assertTrue(q.length() < SamlBindings.MAX_QUERY);
        assertCode("SAML_INFLATE_LIMIT", () -> idp.parseAuthnRequestRedirect(q));
    }

    @Test
    @DisplayName("SSO-T-saml-074 malformed redirect encodings rejected")
    void badRedirect() {
        assertCode("SAML_INFLATE_FAILED", () -> idp.parseAuthnRequestRedirect("SAMLRequest=" + SamlBindings.urlEncode(encode("not deflate"))));
        assertCode("SAML_BINDING", () -> idp.parseAuthnRequestRedirect("SAMLRequest=%zz"));
        assertCode("SAML_BINDING", () -> idp.parseAuthnRequestRedirect("RelayState=x"));
        String q = redirect(request("", ""), null);
        assertCode("SAML_BINDING", () -> idp.parseAuthnRequestRedirect(q + "&" + q));
        assertCode("SAML_BINDING", () -> idp.parseAuthnRequestRedirect(q + "&SigAlg=x"));
    }

    @Test
    @DisplayName("SSO-T-saml-075 POST AuthnRequest with embedded signature verified, tamper rejected")
    void postSigned() {
        String id = Saml.newId();
        byte[] signed = spSigner.sign(request(id, T0, SP_ENTITY, "", "").getBytes(StandardCharsets.UTF_8), id);
        AuthnRequestInfo info = idp.parseAuthnRequestPost(encodeBytes(signed), "r");
        assertTrue(info.signed());
        assertEquals(id, info.id());
        String tampered = new String(signed, StandardCharsets.UTF_8).replace("Version=\"2.0\"",
                "Version=\"2.0\" AssertionConsumerServiceURL=\"" + SP_ACS2 + "\"");
        assertCode("DSIG_DIGEST_MISMATCH", () -> idp.parseAuthnRequestPost(encode(tampered), "r"));
    }

    @Test
    @DisplayName("SSO-T-saml-076 unknown SP issuer rejected")
    void unknownIssuer() {
        assertCode("SAML_UNKNOWN_SP", () -> parseRedirect(request(Saml.newId(), T0, "https://evil.example", "", "")));
        assertCode("SAML_ISSUER", () -> parseRedirect(request("", "<saml:Issuer>" + SP_ENTITY + "</saml:Issuer>")));
    }

    @Test
    @DisplayName("SSO-T-saml-077 ACS URL must exactly match a registered URL")
    void acsExact() {
        assertEquals(SP_ACS2, parseRedirect(request(" AssertionConsumerServiceURL=\"" + SP_ACS2 + "\"", "")).assertionConsumerServiceUrl());
        assertCode("SAML_ACS_MISMATCH", () -> parseRedirect(request(" AssertionConsumerServiceURL=\"https://sp.example.com/acs/\"", "")));
        assertCode("SAML_ACS_MISMATCH", () -> parseRedirect(request(" AssertionConsumerServiceURL=\"https://evil.example/acs\"", "")));
        assertCode("SAML_ACS_MISMATCH", () -> parseRedirect(request(" AssertionConsumerServiceURL=\"HTTPS://sp.example.com/acs\"", "")));
    }

    @Test
    @DisplayName("SSO-T-saml-078 ACS index selects registered URL and is range checked")
    void acsIndex() {
        assertEquals(SP_ACS2, parseRedirect(request(" AssertionConsumerServiceIndex=\"1\"", "")).assertionConsumerServiceUrl());
        assertCode("SAML_ACS_MISMATCH", () -> parseRedirect(request(" AssertionConsumerServiceIndex=\"2\"", "")));
        assertCode("SAML_ACS_MISMATCH", () -> parseRedirect(request(" AssertionConsumerServiceIndex=\"x\"", "")));
    }

    @Test
    @DisplayName("SSO-T-saml-079 request ID format enforced")
    void idFormat() {
        assertCode("SAML_INVALID_ID", () -> parseRedirect(request("1abc", T0, SP_ENTITY, "", "")));
        assertCode("SAML_INVALID_ID", () -> parseRedirect(request("_" + "a".repeat(300), T0, SP_ENTITY, "", "")));
        assertCode("SAML_INVALID_ID", () -> parseRedirect(request("_aé", T0, SP_ENTITY, "", "")));
    }

    @Test
    @DisplayName("SSO-T-saml-080 IssueInstant skew window enforced")
    void issueInstant() {
        parseRedirect(request(Saml.newId(), T0.plus(Duration.ofMinutes(2)), SP_ENTITY, "", ""));
        assertCode("SAML_NOT_YET_VALID", () -> parseRedirect(request(Saml.newId(), T0.plus(Duration.ofMinutes(4)), SP_ENTITY, "", "")));
        assertCode("SAML_EXPIRED", () -> parseRedirect(request(Saml.newId(), T0.minus(Duration.ofMinutes(9)), SP_ENTITY, "", "")));
    }

    @Test
    @DisplayName("SSO-T-saml-081 AuthnRequest replay rejected")
    void replay() {
        String q = redirect(request("", ""), null);
        idp.parseAuthnRequestRedirect(q);
        assertCode("SAML_REPLAY", () -> idp.parseAuthnRequestRedirect(q));
    }

    @Test
    @DisplayName("SSO-T-saml-082 wrong Destination, version, binding and NameID policy rejected")
    void requestFields() {
        assertCode("SAML_DESTINATION", () -> parseRedirect(request("", "").replace(IDP_SSO, "https://other/sso")));
        assertCode("SAML_VERSION", () -> parseRedirect(request("", "").replace("Version=\"2.0\"", "Version=\"1.1\"")));
        assertCode("SAML_UNSUPPORTED_BINDING", () -> parseRedirect(request(
                " ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Artifact\"", "")));
        assertCode("SAML_INVALID_NAMEID_POLICY", () -> parseRedirect(request("",
                "<samlp:NameIDPolicy Format=\"urn:oasis:names:tc:SAML:1.1:nameid-format:X509SubjectName\"/>")));
        assertCode("SAML_UNEXPECTED_MESSAGE", () -> parseRedirect(request("", "").replace("AuthnRequest", "ArtifactResolve")));
    }

    @Test
    @DisplayName("SSO-T-saml-083 NameIDPolicy, ForceAuthn, IsPassive and RequestedAuthnContext parsed")
    void requestOptions() {
        AuthnRequestInfo info = parseRedirect(request(" ForceAuthn=\"true\" IsPassive=\"false\"",
                "<samlp:NameIDPolicy Format=\"" + Saml.NAMEID_PERSISTENT + "\"/>"
                        + "<samlp:RequestedAuthnContext><saml:AuthnContextClassRef>" + Saml.AC_MFA
                        + "</saml:AuthnContextClassRef></samlp:RequestedAuthnContext>"));
        assertEquals(Saml.NAMEID_PERSISTENT, info.nameIdFormat());
        assertTrue(info.forceAuthn());
        assertFalse(info.isPassive());
        assertEquals(List.of(Saml.AC_MFA), info.requestedAuthnContexts());
    }

    @Test
    @DisplayName("SSO-T-saml-084 issued Response carries a complete signed Assertion")
    void responseContent() {
        Flow f = flow();
        XmlDocument d = parser.parse(f.xml());
        XmlElement r = d.root();
        assertEquals(SP_ACS, r.attribute("Destination"));
        assertEquals(f.requestId(), r.attribute("InResponseTo"));
        assertEquals("state-1", f.message().relayState());
        XmlElement a = r.requiredChild(Saml.ASSERTION_NS, "Assertion");
        new XmlSignatureVerifier(List.of(TestKeys.IDP.cert())).verify(d, a);
        XmlElement subj = a.requiredChild(Saml.ASSERTION_NS, "Subject");
        assertEquals("alice@x.com", subj.requiredChild(Saml.ASSERTION_NS, "NameID").text());
        XmlElement scd = subj.requiredChild(Saml.ASSERTION_NS, "SubjectConfirmation")
                .requiredChild(Saml.ASSERTION_NS, "SubjectConfirmationData");
        assertEquals(SP_ACS, scd.attribute("Recipient"));
        assertEquals(f.requestId(), scd.attribute("InResponseTo"));
        assertEquals(Saml.time(T0.plus(Duration.ofMinutes(5))), scd.attribute("NotOnOrAfter"));
        XmlElement cond = a.requiredChild(Saml.ASSERTION_NS, "Conditions");
        assertEquals(SP_ENTITY, cond.requiredChild(Saml.ASSERTION_NS, "AudienceRestriction")
                .requiredChild(Saml.ASSERTION_NS, "Audience").text());
        XmlElement authn = a.requiredChild(Saml.ASSERTION_NS, "AuthnStatement");
        assertEquals("sess-1", authn.attribute("SessionIndex"));
        assertEquals(Saml.AC_MFA, authn.requiredChild(Saml.ASSERTION_NS, "AuthnContext")
                .requiredChild(Saml.ASSERTION_NS, "AuthnContextClassRef").text());
        assertEquals(2, a.requiredChild(Saml.ASSERTION_NS, "AttributeStatement").childElements().size());
        assertTrue(r.childElements(Dsig.NS, "Signature").isEmpty());
    }

    @Test
    @DisplayName("SSO-T-saml-085 persistent NameID is pairwise and stable, transient is random")
    void nameIdFormats() {
        String p1 = idp.persistentId(SP_ENTITY, "user-1");
        assertEquals(p1, idp.persistentId(SP_ENTITY, "user-1"));
        assertNotEquals(p1, idp.persistentId("https://other-sp", "user-1"));
        AuthnRequestInfo info = parseRedirect(request("", "<samlp:NameIDPolicy Format=\"" + Saml.NAMEID_PERSISTENT + "\"/>"));
        XmlElement nameId = responseRoot(idp.issueResponse(info, subject())).requiredChild(Saml.ASSERTION_NS, "Assertion")
                .requiredChild(Saml.ASSERTION_NS, "Subject").requiredChild(Saml.ASSERTION_NS, "NameID");
        assertEquals(p1, nameId.text());
        assertEquals(SP_ENTITY, nameId.attribute("SPNameQualifier"));
        AuthnRequestInfo t = parseRedirect(request("", "<samlp:NameIDPolicy Format=\"" + Saml.NAMEID_TRANSIENT + "\"/>"));
        String t1 = responseRoot(idp.issueResponse(t, subject())).requiredChild(Saml.ASSERTION_NS, "Assertion")
                .requiredChild(Saml.ASSERTION_NS, "Subject").requiredChild(Saml.ASSERTION_NS, "NameID").text();
        assertNotEquals(p1, t1);
        assertTrue(t1.startsWith("_"));
    }

    @Test
    @DisplayName("SSO-T-saml-086 EncryptedAssertion emitted when SP requires encryption")
    void encrypted() {
        registerSp(false, true, true);
        String xml = flow().xml();
        assertFalse(xml.contains("<saml:Assertion"));
        assertFalse(xml.contains("alice@x.com"));
        assertTrue(xml.contains("<saml:EncryptedAssertion>"));
        assertTrue(xml.contains("http://www.w3.org/2009/xmlenc11#aes256-gcm"));
        XmlDocument d = parser.parse(xml);
        new XmlSignatureVerifier(List.of(TestKeys.IDP.cert())).verify(d, d.root());
    }

    @Test
    @DisplayName("SSO-T-saml-087 error Response carries nested status and is signed")
    void errorResponse() {
        AuthnRequestInfo info = parseRedirect(request(" IsPassive=\"true\"", ""));
        XmlDocument d = parser.parse(decode(idp.errorResponse(info, Saml.STATUS_NO_PASSIVE).value()));
        XmlElement top = d.root().requiredChild(Saml.PROTOCOL_NS, "Status").requiredChild(Saml.PROTOCOL_NS, "StatusCode");
        assertEquals(Saml.STATUS_RESPONDER, top.attribute("Value"));
        assertEquals(Saml.STATUS_NO_PASSIVE, top.requiredChild(Saml.PROTOCOL_NS, "StatusCode").attribute("Value"));
        new XmlSignatureVerifier(List.of(TestKeys.IDP.cert())).verify(d, d.root());
        assertTrue(d.root().childElements(Saml.ASSERTION_NS, "Assertion").isEmpty());
    }

    @Test
    @DisplayName("SSO-T-saml-088 email NameID unavailable when subject has no email")
    void noEmail() {
        AuthnRequestInfo info = parseRedirect(request("", ""));
        assertCode("SAML_NAMEID_UNAVAILABLE", () -> idp.issueResponse(info,
                new SamlSubject("u", null, T0, null, "s", null)));
    }

    private String logoutRequest(String id, String issuer) {
        return "<samlp:LogoutRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
                + "xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"" + id + "\" Version=\"2.0\" IssueInstant=\""
                + Saml.time(T0) + "\" Destination=\"" + IDP_SLO + "\"><saml:Issuer>" + issuer + "</saml:Issuer>"
                + "<saml:NameID Format=\"" + Saml.NAMEID_EMAIL + "\">alice@x.com</saml:NameID>"
                + "<samlp:SessionIndex>sess-1</samlp:SessionIndex></samlp:LogoutRequest>";
    }

    @Test
    @DisplayName("SSO-T-saml-089 SLO LogoutRequest verified and answered with signed LogoutResponse")
    void sloFromSp() {
        String id = Saml.newId();
        byte[] signed = spSigner.sign(logoutRequest(id, SP_ENTITY).getBytes(StandardCharsets.UTF_8), id);
        LogoutRequestInfo info = idp.parseLogoutRequestPost(encodeBytes(signed), "r");
        assertEquals("alice@x.com", info.nameId());
        assertEquals(List.of("sess-1"), info.sessionIndexes());
        SamlPostMessage resp = idp.logoutResponse(info, true);
        assertEquals(SP_SLO, resp.destination());
        XmlDocument d = parser.parse(decode(resp.value()));
        new XmlSignatureVerifier(List.of(TestKeys.IDP.cert())).verify(d, d.root());
        assertEquals(id, d.root().attribute("InResponseTo"));
        assertCode("SAML_REPLAY", () -> idp.parseLogoutRequestPost(encodeBytes(signed), "r"));
        String q = query(SamlBindings.encodeRedirect(IDP_SLO, "SAMLRequest",
                logoutRequest(Saml.newId(), SP_ENTITY).getBytes(StandardCharsets.UTF_8), null, spSigner));
        assertEquals("alice@x.com", idp.parseLogoutRequestRedirect(q).nameId());
    }

    @Test
    @DisplayName("SSO-T-saml-090 unsigned SLO LogoutRequest rejected (logout CSRF)")
    void sloUnsigned() {
        assertCode("SAML_SIGNATURE_REQUIRED", () -> idp.parseLogoutRequestPost(encode(logoutRequest(Saml.newId(), SP_ENTITY)), null));
        String q = query(SamlBindings.encodeRedirect(IDP_SLO, "SAMLRequest",
                logoutRequest(Saml.newId(), SP_ENTITY).getBytes(StandardCharsets.UTF_8), null, null));
        assertCode("SAML_SIGNATURE_REQUIRED", () -> idp.parseLogoutRequestRedirect(q));
    }

    @Test
    @DisplayName("SSO-T-saml-091 IdP-initiated LogoutRequest signed, LogoutResponse from SP parsed")
    void idpInitiatedSlo() {
        SamlPostMessage req = idp.logoutRequest(SP_ENTITY, "alice@x.com", Saml.NAMEID_EMAIL, "sess-1", null);
        XmlDocument d = parser.parse(decode(req.value()));
        new XmlSignatureVerifier(List.of(TestKeys.IDP.cert())).verify(d, d.root());
        String rid = Saml.newId();
        String resp = "<samlp:LogoutResponse xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" "
                + "xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\" ID=\"" + rid + "\" Version=\"2.0\" IssueInstant=\""
                + Saml.time(T0) + "\" Destination=\"" + IDP_SLO + "\" InResponseTo=\"" + req.messageId() + "\">"
                + "<saml:Issuer>" + SP_ENTITY + "</saml:Issuer><samlp:Status><samlp:StatusCode Value=\""
                + Saml.STATUS_SUCCESS + "\"/></samlp:Status></samlp:LogoutResponse>";
        byte[] signed = spSigner.sign(resp.getBytes(StandardCharsets.UTF_8), rid);
        LogoutResponseInfo info = idp.parseLogoutResponsePost(encodeBytes(signed), null, req.messageId(), SP_ENTITY);
        assertTrue(info.success());
        assertEquals(req.messageId(), info.inResponseTo());
    }

    @Test
    @DisplayName("SSO-T-saml-092 RelayState length capped and POST form escapes values")
    void relayAndForm() {
        assertCode("SAML_RELAY_STATE_TOO_LONG", () -> idp.parseAuthnRequestPost(encode(request("", "")), "x".repeat(2000)));
        SamlPostMessage m = new SamlPostMessage("https://sp/acs?a=1&b=\"2\"", "SAMLResponse", "abc", "<script>", "_id");
        String html = m.htmlForm();
        assertTrue(html.contains("action=\"https://sp/acs?a=1&amp;b=&quot;2&quot;\""));
        assertTrue(html.contains("value=\"&lt;script&gt;\""));
        assertFalse(html.contains("<script>"));
    }

    @Test
    @DisplayName("SSO-T-saml-093 XXE and DOCTYPE in POST AuthnRequest rejected")
    void postXxe() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + request("", "");
        assertCode("XML_DOCTYPE", () -> idp.parseAuthnRequestPost(encode(xml), null));
        String q = redirect(xml, null);
        assertCode("XML_DOCTYPE", () -> idp.parseAuthnRequestRedirect(q));
    }

    @Test
    @DisplayName("SSO-T-saml-094 embedded signature inside redirect message rejected")
    void redirectEmbeddedSignature() {
        String id = Saml.newId();
        String signed = new String(spSigner.sign(request(id, T0, SP_ENTITY, "", "").getBytes(StandardCharsets.UTF_8), id),
                StandardCharsets.UTF_8);
        assertCode("SAML_BINDING", () -> idp.parseAuthnRequestRedirect(redirect(signed, null)));
    }

    @Test
    @DisplayName("SSO-T-saml-095 ECDSA IdP key signs responses")
    void ecdsaIdp() {
        SamlIdp ecIdp = new SamlIdp(SamlIdpConfig.of(IDP_ENTITY, IDP_SSO, IDP_SLO, TestKeys.IDP_EC.key(),
                TestKeys.IDP_EC.cert(), PAIRWISE), registry, new InMemoryReplayCache(clock), clock);
        AuthnRequestInfo info = ecIdp.parseAuthnRequestRedirect(redirect(request("", ""), null));
        XmlDocument d = parser.parse(decode(ecIdp.issueResponse(info, subject()).value()));
        XmlElement a = d.root().requiredChild(Saml.ASSERTION_NS, "Assertion");
        new XmlSignatureVerifier(List.of(TestKeys.IDP_EC.cert())).verify(d, a);
        assertTrue(XmlSerializer.serialize(a).contains(Dsig.ECDSA_SHA256));
    }

    @Test
    @DisplayName("SSO-T-saml-132 signed AuthnRequest or LogoutRequest without Destination rejected (SAML bindings 3.4.5.2, 3.5.5.2); unsigned still accepted")
    void signedRequiresDestination() {
        String id = Saml.newId();
        String noDest = request(id, T0, SP_ENTITY, "", "").replace(" Destination=\"" + IDP_SSO + "\"", "");
        byte[] signed = spSigner.sign(noDest.getBytes(StandardCharsets.UTF_8), id);
        assertCode("SAML_DESTINATION", () -> idp.parseAuthnRequestPost(encodeBytes(signed), "r"));
        String noDest2 = request(Saml.newId(), T0, SP_ENTITY, "", "").replace(" Destination=\"" + IDP_SSO + "\"", "");
        assertCode("SAML_DESTINATION", () -> idp.parseAuthnRequestRedirect(redirect(noDest2, spSigner)));
        String noDest3 = request(Saml.newId(), T0, SP_ENTITY, "", "").replace(" Destination=\"" + IDP_SSO + "\"", "");
        assertFalse(parseRedirect(noDest3).signed());
        String lid = Saml.newId();
        String logout = logoutRequest(lid, SP_ENTITY).replace(" Destination=\"" + IDP_SLO + "\"", "");
        byte[] signedLogout = spSigner.sign(logout.getBytes(StandardCharsets.UTF_8), lid);
        assertCode("SAML_DESTINATION", () -> idp.parseLogoutRequestPost(encodeBytes(signedLogout), "r"));
    }

    @Test
    @DisplayName("SSO-T-saml-133 AssertionConsumerServiceIndex accepts ASCII digits only (no sign, no non-ASCII digits)")
    void acsIndexStrict() {
        assertCode("SAML_ACS_MISMATCH", () -> parseRedirect(request(" AssertionConsumerServiceIndex=\"+1\"", "")));
        assertCode("SAML_ACS_MISMATCH", () -> parseRedirect(request(" AssertionConsumerServiceIndex=\"\u0661\"", "")));
        assertCode("SAML_ACS_MISMATCH", () -> parseRedirect(request(" AssertionConsumerServiceIndex=\"-0\"", "")));
        assertEquals(SP_ACS2, parseRedirect(request(" AssertionConsumerServiceIndex=\"1\"", "")).assertionConsumerServiceUrl());
    }

    @Test
    @DisplayName("SSO-T-saml-142 a ServiceProvider refuses an ACS or SLO URL that would carry the signed assertion or logout message off https: plain http outside loopback, javascript:, data:, relative, userinfo, fragment, wildcard, whitespace; https and loopback http stay accepted")
    void serviceProviderUrlsMustBeHttps() {
        List<String> bad = List.of("http://sp.example.com/acs", "javascript:alert(1)", "data:text/html,x", "/acs", "sp.example.com/acs",
                "https://user@sp.example.com/acs", "https://sp.example.com/acs#f", "https://*.example.com/acs", "https://sp.example.com/a cs",
                "ftp://sp.example.com/acs", "custom.app:/acs", "");
        for (String url : bad) {
            assertCode("SAML_SP_CONFIG", () -> ServiceProvider.of(SP_ENTITY, List.of(SP_ACS, url), List.of()));
            assertCode("SAML_SP_CONFIG", () -> new ServiceProvider(SP_ENTITY, List.of(SP_ACS), url, List.of(), null, false, false, false,
                    null, null));
        }
        assertCode("SAML_SP_CONFIG", () -> ServiceProvider.of(SP_ENTITY, java.util.Arrays.asList(SP_ACS, null), List.of()));
        assertEquals(List.of(SP_ACS, "http://localhost:8080/acs", "http://127.0.0.1/acs"),
                ServiceProvider.of(SP_ENTITY, List.of(SP_ACS, "http://localhost:8080/acs", "http://127.0.0.1/acs"), List.of())
                        .assertionConsumerServiceUrls());
        assertEquals(SP_SLO, new ServiceProvider(SP_ENTITY, List.of(SP_ACS), SP_SLO, List.of(), null, false, false, false, null, null)
                .singleLogoutServiceUrl());
    }
}
