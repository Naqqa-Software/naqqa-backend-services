package com.naqqa.sso.saml;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.dsig.Dsig;
import com.naqqa.sso.saml.idp.AuthnRequestInfo;
import com.naqqa.sso.saml.idp.SamlIdp;
import com.naqqa.sso.saml.idp.SamlIdpConfig;
import com.naqqa.sso.saml.sp.OutboundAuthnRequest;
import com.naqqa.sso.saml.sp.SamlPrincipal;
import com.naqqa.sso.saml.sp.SamlSp;
import com.naqqa.sso.saml.sp.SamlSpConfig;
import com.naqqa.sso.saml.xml.XmlElement;
import com.naqqa.sso.saml.xml.XmlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlSpTest extends SamlTestBase {

    private SamlPrincipal verify(Flow f) {
        return sp.verifyResponse(f.message().value(), f.requestId());
    }

    private SamlPrincipal verify(Flow f, String xml) {
        return sp.verifyResponse(encode(xml), f.requestId());
    }

    private void rejects(String code, Flow f, String xml) {
        assertCode(code, () -> verify(f, xml));
    }

    private static String evil(String assertion) {
        return stripSignature(assertion).replace("alice@x.com", "admin@x.com");
    }

    @Test
    @DisplayName("SSO-T-saml-096 round trip SP AuthnRequest -> IdP Response -> SP principal")
    void roundTrip() {
        Flow f = flow();
        SamlPrincipal p = verify(f);
        assertEquals(IDP_ENTITY, p.issuer());
        assertEquals("alice@x.com", p.nameId());
        assertEquals(Saml.NAMEID_EMAIL, p.nameIdFormat());
        assertEquals("sess-1", p.sessionIndex());
        assertEquals(Saml.AC_MFA, p.authnContextClassRef());
        assertEquals(T0.minusSeconds(60), p.authnInstant());
        assertEquals(List.of("admins", "dev"), p.attributes().get("groups"));
        assertEquals("alice@x.com", p.firstAttribute("email"));
        assertEquals(f.requestId(), p.inResponseTo());
        assertEquals(T0.plus(Duration.ofMinutes(5)), p.expiresAt());
    }

    @Test
    @DisplayName("SSO-T-saml-097 round trip with signed Response and EncryptedAssertion")
    void roundTripEncrypted() {
        registerSp(true, true, true);
        sp = newSp(spConfig.withPolicy(true, true, true, false));
        assertEquals("alice@x.com", verify(flow()).nameId());
    }

    @Test
    @DisplayName("SSO-T-saml-098 round trip with ECDSA IdP key")
    void roundTripEcdsa() {
        idp = new SamlIdp(SamlIdpConfig.of(IDP_ENTITY, IDP_SSO, IDP_SLO, TestKeys.IDP_EC.key(), TestKeys.IDP_EC.cert(), PAIRWISE),
                registry, idpReplay, clock);
        sp = newSp(new SamlSpConfig(SP_ENTITY, SP_ACS, IDP_ENTITY, IDP_SSO, List.of(TestKeys.IDP.cert(), TestKeys.IDP_EC.cert()),
                null, null, null, true, false, false, false, null, null, null));
        assertEquals("alice@x.com", verify(flow()).nameId());
    }

    @Test
    @DisplayName("SSO-T-saml-099 unsigned assertion rejected")
    void unsignedAssertion() {
        Flow f = flow();
        rejects("SAML_SIGNATURE_REQUIRED", f, stripSignature(f.xml()));
    }

    @Test
    @DisplayName("SSO-T-saml-100 response-only signature accepted only when policy allows")
    void responseOnlySignature() {
        registerSp(false, true, false);
        Flow f = flow();
        String xml = f.xml();
        String a = assertionOf(xml);
        String noAssertionSig = xml.replace(a, stripSignature(a));
        rejects("DSIG_DIGEST_MISMATCH", f, noAssertionSig);
        Flow g = flow();
        String gx = g.xml();
        String ga = assertionOf(gx);
        String responseSigned = new String(new com.naqqa.sso.saml.dsig.XmlSigner(TestKeys.IDP.key(), TestKeys.IDP.cert())
                .sign(stripSignature(gx.replace(ga, stripSignature(ga))).getBytes(StandardCharsets.UTF_8), idOf(gx)),
                StandardCharsets.UTF_8);
        assertCode("SAML_SIGNATURE_REQUIRED", () -> sp.verifyResponse(encode(responseSigned), g.requestId()));
        sp = newSp(spConfig.withPolicy(false, true, false, false));
        Flow h = flow();
        String hx = h.xml();
        String ha = assertionOf(hx);
        String hSigned = new String(new com.naqqa.sso.saml.dsig.XmlSigner(TestKeys.IDP.key(), TestKeys.IDP.cert())
                .sign(stripSignature(hx.replace(ha, stripSignature(ha))).getBytes(StandardCharsets.UTF_8), idOf(hx)),
                StandardCharsets.UTF_8);
        assertEquals("alice@x.com", sp.verifyResponse(encode(hSigned), h.requestId()).nameId());
        Flow i = flow();
        assertCode("SAML_SIGNATURE_REQUIRED", () -> sp.verifyResponse(encode(stripSignature(i.xml())), i.requestId()));
    }

    @Test
    @DisplayName("SSO-T-saml-101 wrapping: signed assertion moved into Extensions with evil assertion as child")
    void wrapExtensions() {
        Flow f = flow();
        String xml = f.xml();
        String a = assertionOf(xml);
        String attacked = xml.replace(a, "<samlp:Extensions>" + a + "</samlp:Extensions>" + evil(a).replace(idOf(a), "_evil"));
        rejects("SAML_ASSERTION_COUNT", f, attacked);
    }

    @Test
    @DisplayName("SSO-T-saml-102 wrapping: extra unsigned assertion before or after the signed one")
    void wrapExtraAssertion() {
        Flow f = flow();
        String xml = f.xml();
        String a = assertionOf(xml);
        rejects("SAML_ASSERTION_COUNT", f, xml.replace(a, evil(a).replace(idOf(a), "_evil") + a));
        rejects("SAML_ASSERTION_COUNT", f, xml.replace(a, a + evil(a).replace(idOf(a), "_evil")));
    }

    @Test
    @DisplayName("SSO-T-saml-103 wrapping: evil assertion carrying the original signature, original hidden in ds:Object")
    void wrapObject() {
        Flow f = flow();
        String xml = f.xml();
        String a = assertionOf(xml);
        int s = a.indexOf("<ds:Signature");
        int e = a.indexOf("</ds:Signature>");
        String sig = a.substring(s, e);
        String evil = evil(a).replace(idOf(a), "_evil");
        int insert = evil.indexOf("</saml:Issuer>") + "</saml:Issuer>".length();
        String wrapped = evil.substring(0, insert) + sig + "<ds:Object>" + a + "</ds:Object></ds:Signature>" + evil.substring(insert);
        rejects("SAML_ASSERTION_COUNT", f, xml.replace(a, wrapped));
    }

    @Test
    @DisplayName("SSO-T-saml-104 wrapping: evil assertion reusing the signed ID collides as duplicate")
    void wrapDuplicateId() {
        Flow f = flow();
        String xml = f.xml();
        String a = assertionOf(xml);
        String withExt = xml.replace("<samlp:Status>", "<samlp:Extensions><x:Hide xmlns:x=\"urn:x\" ID=\"" + idOf(a)
                + "\"/></samlp:Extensions><samlp:Status>");
        rejects("DSIG_DUPLICATE_ID", f, withExt);
    }

    @Test
    @DisplayName("SSO-T-saml-105 wrapping: copied signature on evil assertion references the original ID")
    void wrapCopiedSignature() {
        Flow f = flow();
        String xml = f.xml();
        String a = assertionOf(xml);
        int s = a.indexOf("<ds:Signature");
        int e = a.indexOf("</ds:Signature>") + "</ds:Signature>".length();
        String sig = a.substring(s, e);
        String evil = evil(a).replace(idOf(a), "_evil");
        int insert = evil.indexOf("</saml:Issuer>") + "</saml:Issuer>".length();
        rejects("DSIG_REFERENCE_MISMATCH", f, xml.replace(a, evil.substring(0, insert) + sig + evil.substring(insert)));
    }

    @Test
    @DisplayName("SSO-T-saml-106 wrapping: assertion signature referencing the Response element")
    void referenceOther() {
        Flow f = flow();
        String xml = f.xml();
        rejects("DSIG_REFERENCE_MISMATCH", f, xml.replace("URI=\"#" + idOf(assertionOf(xml)) + "\"", "URI=\"#" + idOf(xml) + "\""));
    }

    @Test
    @DisplayName("SSO-T-saml-107 wrapping: signed Response with injected unsigned assertion after signature")
    void wrapSignedResponse() {
        registerSp(false, true, false);
        Flow f = flow();
        String xml = f.xml();
        String a = assertionOf(xml);
        rejects("DSIG_DIGEST_MISMATCH", f, xml.replace("</samlp:Response>", evil(a).replace(idOf(a), "_evil") + "</samlp:Response>"));
    }

    @Test
    @DisplayName("SSO-T-saml-108 comment injection in NameID yields the full verified text")
    void commentInjection() {
        Flow f = flow();
        String xml = resignAssertion(f.xml(), a -> a.replace(">alice@x.com</saml:NameID>", ">alice@x.com.evil.com</saml:NameID>"));
        String injected = xml.replace(">alice@x.com.evil.com</saml:NameID>", ">alice@x.com<!---->.evil.com</saml:NameID>");
        assertEquals("alice@x.com.evil.com", verify(f, injected).nameId());
    }

    @Test
    @DisplayName("SSO-T-saml-109 NameID or attribute tamper detected by digest")
    void tamper() {
        Flow f = flow();
        rejects("DSIG_DIGEST_MISMATCH", f, f.xml().replace(">alice@x.com</saml:NameID>", ">admin@x.com</saml:NameID>"));
        rejects("DSIG_DIGEST_MISMATCH", f, f.xml().replace(">admins<", ">root<"));
    }

    @Test
    @DisplayName("SSO-T-saml-110 signature value tamper rejected")
    void sigTamper() {
        Flow f = flow();
        String xml = f.xml();
        int i = xml.indexOf("<ds:SignatureValue>") + 30;
        rejects("DSIG_SIGNATURE_INVALID", f, xml.substring(0, i) + (xml.charAt(i) == 'A' ? 'B' : 'A') + xml.substring(i + 1));
    }

    @Test
    @DisplayName("SSO-T-saml-111 algorithm downgrade to rsa-sha1 or hmac rejected")
    void downgrade() {
        Flow f = flow();
        rejects("DSIG_ALGORITHM", f, f.xml().replace(Dsig.RSA_SHA256, "http://www.w3.org/2000/09/xmldsig#rsa-sha1"));
        rejects("DSIG_ALGORITHM", f, f.xml().replace(Dsig.RSA_SHA256, "http://www.w3.org/2000/09/xmldsig#hmac-sha1"));
        rejects("DSIG_ALGORITHM", f, f.xml().replace(Dsig.SHA256, "http://www.w3.org/2000/09/xmldsig#sha1"));
    }

    @Test
    @DisplayName("SSO-T-saml-112 assertion signed by attacker with attacker KeyInfo rejected")
    void attackerSigned() {
        Flow f = flow();
        rejects("DSIG_SIGNATURE_INVALID", f, resignAssertion(f.xml(), a -> a.replace("alice@x.com", "admin@x.com"), TestKeys.ATTACKER));
    }

    @Test
    @DisplayName("SSO-T-saml-113 expired assertion rejected after skew")
    void expired() {
        Flow f = flow();
        advance(Duration.ofMinutes(8));
        assertCode("SAML_EXPIRED", () -> verify(f));
    }

    @Test
    @DisplayName("SSO-T-saml-114 assertion within clock skew accepted")
    void withinSkew() {
        Flow f = flow();
        advance(Duration.ofMinutes(8).minusSeconds(1));
        sp = newSp(new SamlSpConfig(SP_ENTITY, SP_ACS, IDP_ENTITY, IDP_SSO, List.of(TestKeys.IDP.cert()),
                null, null, null, true, false, false, false, Duration.ofMinutes(3), Duration.ofMinutes(30), null));
        assertEquals("alice@x.com", verify(f).nameId());
    }

    @Test
    @DisplayName("SSO-T-saml-115 not-yet-valid assertion rejected")
    void notYetValid() {
        Flow f = flow();
        clock.set(T0.minus(Duration.ofMinutes(10)));
        assertCode("SAML_NOT_YET_VALID", () -> verify(f));
    }

    @Test
    @DisplayName("SSO-T-saml-116 wrong audience rejected")
    void wrongAudience() {
        Flow f = flow();
        SamlSp other = newSp(new SamlSpConfig("https://other-sp.example.com", SP_ACS, IDP_ENTITY, IDP_SSO,
                List.of(TestKeys.IDP.cert()), null, null, null, true, false, false, false, null, null, null));
        assertCode("SAML_AUDIENCE", () -> other.verifyResponse(f.message().value(), f.requestId()));
        Flow g = flow();
        rejects("SAML_AUDIENCE", g, resignAssertion(g.xml(), a -> a.replaceAll("<saml:AudienceRestriction>.*</saml:AudienceRestriction>", "")));
    }

    @Test
    @DisplayName("SSO-T-saml-117 wrong destination and recipient rejected")
    void wrongRecipient() {
        Flow f = flow();
        SamlSp other = newSp(new SamlSpConfig(SP_ENTITY, SP_ACS2, IDP_ENTITY, IDP_SSO,
                List.of(TestKeys.IDP.cert()), null, null, null, true, false, false, false, null, null, null));
        assertCode("SAML_DESTINATION", () -> other.verifyResponse(f.message().value(), f.requestId()));
        assertCode("SAML_RECIPIENT", () -> other.verifyResponse(encode(f.xml().replace(
                "Destination=\"" + SP_ACS + "\"", "Destination=\"" + SP_ACS2 + "\"")), f.requestId()));
    }

    @Test
    @DisplayName("SSO-T-saml-118 assertion replay rejected")
    void replay() {
        Flow f = flow();
        verify(f);
        assertCode("SAML_REPLAY", () -> verify(f));
    }

    @Test
    @DisplayName("SSO-T-saml-119 InResponseTo mismatch and unknown request rejected")
    void inResponseTo() {
        Flow f = flow();
        OutboundAuthnRequest other = sp.createAuthnRequest(null);
        assertCode("SAML_IN_RESPONSE_TO", () -> sp.verifyResponse(f.message().value(), other.id()));
        AuthnRequestInfo forged = new AuthnRequestInfo("_neverIssued", SP_ENTITY, SP_ACS, null, Saml.NAMEID_EMAIL,
                false, false, null, T0, false);
        SamlPostMessage m = idp.issueResponse(forged, subject());
        assertCode("SAML_UNKNOWN_REQUEST", () -> sp.verifyResponse(m.value(), "_neverIssued"));
    }

    @Test
    @DisplayName("SSO-T-saml-120 unsolicited responses rejected unless allowed")
    void unsolicited() {
        Flow f = flow();
        assertCode("SAML_UNSOLICITED", () -> sp.verifyResponse(f.message().value(), null));
        SamlSp open = newSp(spConfig.withPolicy(true, false, false, true));
        assertCode("SAML_IN_RESPONSE_TO", () -> open.verifyResponse(f.message().value(), null));
    }

    @Test
    @DisplayName("SSO-T-saml-121 non-success status rejected")
    void status() {
        OutboundAuthnRequest out = sp.createAuthnRequest(null);
        AuthnRequestInfo info = idp.parseAuthnRequestRedirect(query(out.redirectUrl()));
        SamlPostMessage err = idp.errorResponse(info, Saml.STATUS_AUTHN_FAILED);
        assertCode("SAML_STATUS_NOT_SUCCESS", () -> sp.verifyResponse(err.value(), out.id()));
    }

    @Test
    @DisplayName("SSO-T-saml-122 assertion from another issuer rejected")
    void wrongIssuer() {
        Flow f = flow();
        SamlSp other = newSp(new SamlSpConfig(SP_ENTITY, SP_ACS, "https://other-idp", IDP_SSO,
                List.of(TestKeys.IDP.cert()), null, null, null, true, false, false, false, null, null, null));
        assertCode("SAML_ISSUER", () -> other.verifyResponse(f.message().value(), f.requestId()));
    }

    @Test
    @DisplayName("SSO-T-saml-123 decryption with wrong key and missing encryption rejected")
    void decryptFailures() {
        registerSp(false, false, true);
        Flow f = flow();
        SamlSp wrongKey = newSp(spConfig.withDecryptionKey(TestKeys.ATTACKER.key()));
        assertCode("SAML_DECRYPT_FAILED", () -> wrongKey.verifyResponse(f.message().value(), f.requestId()));
        registerSp(false, false, false);
        Flow g = flow();
        SamlSp strict = newSp(spConfig.withPolicy(true, false, true, false));
        assertCode("SAML_ENCRYPTION_REQUIRED", () -> strict.verifyResponse(g.message().value(), g.requestId()));
    }

    @Test
    @DisplayName("SSO-T-saml-124 encrypted assertion forged by anyone with the SP public key still needs IdP signature")
    void forgedEncrypted() {
        Flow f = flow();
        String xml = f.xml();
        String a = assertionOf(xml);
        String forged = com.naqqa.sso.saml.dsig.XmlEncryption.encrypt(
                evil(a).getBytes(StandardCharsets.UTF_8), TestKeys.SP_ENC.cert().getPublicKey());
        rejects("SAML_SIGNATURE_REQUIRED", f, xml.replace(a, "<saml:EncryptedAssertion>" + forged + "</saml:EncryptedAssertion>"));
    }

    @Test
    @DisplayName("SSO-T-saml-125 XXE, DOCTYPE, deep nesting, oversized and non UTF-8 responses rejected")
    void hostileInput() {
        Flow f = flow();
        rejects("XML_DOCTYPE", f, "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + f.xml());
        String deep = f.xml().replace("<samlp:Status>", "<samlp:Extensions>" + "<x:a xmlns:x=\"urn:x\">".repeat(40)
                + "</x:a>".repeat(40) + "</samlp:Extensions><samlp:Status>");
        rejects("XML_TOO_DEEP", f, deep);
        String big = f.xml().replace("<samlp:Status>", "<samlp:Extensions><x:a xmlns:x=\"urn:x\">" + "A".repeat(300 * 1024)
                + "</x:a></samlp:Extensions><samlp:Status>");
        SsoException e = assertThrows(SsoException.class, () -> verify(f, big));
        assertTrue(e.code().equals("SAML_MESSAGE_TOO_LARGE") || e.code().equals("XML_TOO_LARGE"));
        byte[] latin1 = f.xml().replace("alice@x.com</saml:NameID>", "alïce</saml:NameID>").getBytes(StandardCharsets.ISO_8859_1);
        assertCode("XML_ENCODING", () -> sp.verifyResponse(encodeBytes(latin1), f.requestId()));
        assertCode("SAML_BINDING", () -> sp.verifyResponse("!!!not-base64!!!", f.requestId()));
    }

    @Test
    @DisplayName("SSO-T-saml-126 unknown conditions and missing bearer confirmation rejected")
    void conditionsAndConfirmation() {
        Flow f = flow();
        rejects("SAML_UNKNOWN_CONDITION", f, resignAssertion(f.xml(), a -> a.replace("</saml:AudienceRestriction>",
                "</saml:AudienceRestriction><saml:Condition xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:type=\"x:Custom\"/>")));
        Flow g = flow();
        rejects("SAML_SUBJECT_CONFIRMATION", g, resignAssertion(g.xml(), a -> a.replace(Saml.CM_BEARER,
                "urn:oasis:names:tc:SAML:2.0:cm:holder-of-key")));
        Flow h = flow();
        rejects("SAML_EXPIRED", h, resignAssertion(h.xml(), a -> a.replaceFirst(
                "NotOnOrAfter=\"[^\"]+\" Recipient", "NotOnOrAfter=\"" + Saml.time(T0.minus(Duration.ofMinutes(10))) + "\" Recipient")));
    }

    @Test
    @DisplayName("SSO-T-saml-127 SubjectConfirmationData InResponseTo must match even if Response attribute is removed")
    void scdInResponseTo() {
        Flow f = flow();
        OutboundAuthnRequest other = sp.createAuthnRequest(null);
        String xml = f.xml().replace(" InResponseTo=\"" + f.requestId() + "\" IssueInstant", " IssueInstant");
        String noResponseIrt = xml.replaceFirst(" InResponseTo=\"" + f.requestId() + "\"", "");
        assertCode("SAML_IN_RESPONSE_TO", () -> sp.verifyResponse(encode(noResponseIrt), other.id()));
    }

    @Test
    @DisplayName("SSO-T-saml-128 SP metadata and AuthnRequest shape")
    void spMetadata() {
        XmlElement ed = new XmlParser().parse(sp.metadata(TestKeys.SP_ENC.cert())).root();
        XmlElement d = ed.requiredChild(Saml.METADATA_NS, "SPSSODescriptor");
        assertEquals("true", d.attribute("AuthnRequestsSigned"));
        assertEquals("true", d.attribute("WantAssertionsSigned"));
        assertEquals(2, d.childElements(Saml.METADATA_NS, "KeyDescriptor").size());
        assertEquals(SP_ACS, d.requiredChild(Saml.METADATA_NS, "AssertionConsumerService").attribute("Location"));
        OutboundAuthnRequest out = sp.createAuthnRequest("rs");
        assertTrue(out.redirectUrl().startsWith(IDP_SSO + "?SAMLRequest="));
        SamlBindings.RedirectMessage m = SamlBindings.decodeRedirect(query(out.redirectUrl()), "SAMLRequest", 65536);
        XmlElement req = new XmlParser().parse(m.xml()).root();
        assertEquals(out.id(), req.attribute("ID"));
        assertEquals(SP_ACS, req.attribute("AssertionConsumerServiceURL"));
    }

    @Test
    @DisplayName("SSO-T-saml-129 SP config rejects policies that accept unsigned assertions")
    void spConfigPolicy() {
        assertCode("SAML_SP_CONFIG", () -> spConfig.withPolicy(false, false, false, false));
        assertCode("SAML_SP_CONFIG", () -> SamlSpConfig.of(SP_ENTITY, SP_ACS, IDP_ENTITY, IDP_SSO, List.of()));
    }

    @Test
    @DisplayName("SSO-T-saml-130 request ID consumed once: second response for the same request rejected")
    void requestConsumedOnce() {
        OutboundAuthnRequest out = sp.createAuthnRequest(null);
        AuthnRequestInfo info = idp.parseAuthnRequestRedirect(query(out.redirectUrl()));
        SamlPostMessage first = idp.issueResponse(info, subject());
        SamlPostMessage second = idp.issueResponse(info, subject());
        sp.verifyResponse(first.value(), out.id());
        assertCode("SAML_UNKNOWN_REQUEST", () -> sp.verifyResponse(second.value(), out.id()));
    }

    @Test
    @DisplayName("SSO-T-saml-131 replay cache fails closed when full")
    void replayCacheFull() {
        InMemoryReplayCache cache = new InMemoryReplayCache(clock, 2);
        assertTrue(cache.markIfAbsent("a", T0.plusSeconds(60)));
        assertTrue(cache.markIfAbsent("b", T0.plusSeconds(60)));
        assertTrue(!cache.markIfAbsent("a", T0.plusSeconds(60)));
        assertCode("SAML_REPLAY_CACHE_FULL", () -> cache.markIfAbsent("c", T0.plusSeconds(60)));
        advance(Duration.ofMinutes(2));
        assertTrue(cache.markIfAbsent("c", T0.plusSeconds(600)));
        assertEquals(1, cache.size());
    }
}
