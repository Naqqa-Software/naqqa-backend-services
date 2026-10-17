package com.naqqa.sso.saml;

import com.naqqa.sso.SsoException;
import com.naqqa.sso.saml.dsig.XmlSigner;
import com.naqqa.sso.saml.idp.AuthnRequestInfo;
import com.naqqa.sso.saml.idp.InMemoryServiceProviderRegistry;
import com.naqqa.sso.saml.idp.SamlIdp;
import com.naqqa.sso.saml.idp.SamlIdpConfig;
import com.naqqa.sso.saml.idp.SamlSubject;
import com.naqqa.sso.saml.idp.ServiceProvider;
import com.naqqa.sso.saml.sp.InMemorySamlRequestStore;
import com.naqqa.sso.saml.sp.OutboundAuthnRequest;
import com.naqqa.sso.saml.sp.SamlSp;
import com.naqqa.sso.saml.sp.SamlSpConfig;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

abstract class SamlTestBase {
    static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");
    static final String IDP_ENTITY = "https://idp.naqqa.test";
    static final String IDP_SSO = "https://idp.naqqa.test/saml2/idp/sso";
    static final String IDP_SLO = "https://idp.naqqa.test/saml2/idp/slo";
    static final String SP_ENTITY = "https://sp.example.com";
    static final String SP_ACS = "https://sp.example.com/acs";
    static final String SP_ACS2 = "https://sp.example.com/acs2";
    static final String SP_SLO = "https://sp.example.com/slo";
    static final byte[] PAIRWISE = new byte[32];

    final MutableClock clock = new MutableClock(T0);
    final InMemoryServiceProviderRegistry registry = new InMemoryServiceProviderRegistry();
    final InMemoryReplayCache idpReplay = new InMemoryReplayCache(clock);
    final InMemoryReplayCache spReplay = new InMemoryReplayCache(clock);
    final InMemorySamlRequestStore requestStore = new InMemorySamlRequestStore(clock);
    SamlIdp idp;
    SamlSp sp;
    SamlSpConfig spConfig;

    SamlTestBase() {
        registerSp(false, false, false);
        idp = new SamlIdp(SamlIdpConfig.of(IDP_ENTITY, IDP_SSO, IDP_SLO, TestKeys.IDP.key(), TestKeys.IDP.cert(), PAIRWISE),
                registry, idpReplay, clock);
        spConfig = SamlSpConfig.of(SP_ENTITY, SP_ACS, IDP_ENTITY, IDP_SSO, List.of(TestKeys.IDP.cert()))
                .withSigning(TestKeys.SP.key(), TestKeys.SP.cert())
                .withDecryptionKey(TestKeys.SP_ENC.key());
        sp = newSp(spConfig);
    }

    SamlSp newSp(SamlSpConfig config) {
        return new SamlSp(config, requestStore, spReplay, clock);
    }

    void registerSp(boolean requireSigned, boolean signResponse, boolean encrypt) {
        registry.register(new ServiceProvider(SP_ENTITY, List.of(SP_ACS, SP_ACS2), SP_SLO, List.of(TestKeys.SP.cert()),
                TestKeys.SP_ENC.cert(), requireSigned, signResponse, encrypt, null, null));
    }

    static SamlSubject subject() {
        return new SamlSubject("user-1", "alice@x.com", T0.minusSeconds(60), "mfa", "sess-1",
                Map.of("email", List.of("alice@x.com"), "groups", List.of("admins", "dev")));
    }

    static String query(String url) {
        return url.substring(url.indexOf('?') + 1);
    }

    record Flow(String requestId, SamlPostMessage message) {
        String xml() {
            return decode(message.value());
        }
    }

    Flow flow() {
        OutboundAuthnRequest out = sp.createAuthnRequest("state-1");
        AuthnRequestInfo info = idp.parseAuthnRequestRedirect(query(out.redirectUrl()));
        return new Flow(out.id(), idp.issueResponse(info, subject()));
    }

    static String decode(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    static String encode(String xml) {
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    static String encodeBytes(byte[] xml) {
        return Base64.getEncoder().encodeToString(xml);
    }

    static String assertionOf(String responseXml) {
        int s = responseXml.indexOf("<saml:Assertion");
        int e = responseXml.indexOf("</saml:Assertion>") + "</saml:Assertion>".length();
        return responseXml.substring(s, e);
    }

    static String stripSignature(String xml) {
        int s = xml.indexOf("<ds:Signature");
        int e = xml.indexOf("</ds:Signature>") + "</ds:Signature>".length();
        return xml.substring(0, s) + xml.substring(e);
    }

    static String idOf(String elementXml) {
        int i = elementXml.indexOf(" ID=\"") + 5;
        return elementXml.substring(i, elementXml.indexOf('"', i));
    }

    static String resignAssertion(String responseXml, UnaryOperator<String> edit, TestKeys.Cred cred) {
        String assertion = assertionOf(responseXml);
        String edited = edit.apply(stripSignature(assertion));
        String signed = new String(new XmlSigner(cred.key(), cred.cert()).sign(edited.getBytes(StandardCharsets.UTF_8),
                idOf(edited)), StandardCharsets.UTF_8);
        return responseXml.replace(assertion, signed);
    }

    static String resignAssertion(String responseXml, UnaryOperator<String> edit) {
        return resignAssertion(responseXml, edit, TestKeys.IDP);
    }

    static void assertCode(String code, Executable e) {
        SsoException ex = assertThrows(SsoException.class, e);
        assertEquals(code, ex.code());
    }

    static Set<String> codes(String... c) {
        return Set.of(c);
    }

    void advance(Duration d) {
        clock.advance(d);
    }
}
