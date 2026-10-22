package com.naqqa.sso.chain;

import com.naqqa.sso.mfa.Factor;
import com.naqqa.sso.mfa.MfaPolicy;
import com.naqqa.sso.mfa.StepUp;
import com.naqqa.sso.oidc.GrantTypes;
import com.naqqa.sso.oidc.Interaction;
import com.naqqa.sso.oidc.OidcResponse;
import com.naqqa.sso.saml.InMemoryReplayCache;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.saml.TestKeys;
import com.naqqa.sso.saml.idp.AuthnRequestInfo;
import com.naqqa.sso.saml.idp.InMemoryServiceProviderRegistry;
import com.naqqa.sso.saml.idp.SamlIdp;
import com.naqqa.sso.saml.idp.SamlIdpConfig;
import com.naqqa.sso.saml.idp.SamlSubject;
import com.naqqa.sso.saml.idp.ServiceProvider;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.naqqa.sso.chain.ChainFixture.HIGH_SECRET;
import static com.naqqa.sso.chain.ChainFixture.T1;
import static com.naqqa.sso.chain.ChainFixture.basic;
import static com.naqqa.sso.chain.ChainFixture.get;
import static com.naqqa.sso.chain.ChainFixture.params;
import static com.naqqa.sso.chain.ChainFixture.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DowngradeChainTest {
    private static final String HIGH_CB = "https://admin.example.com/cb";
    private final ChainFixture f = new ChainFixture();

    private OidcResponse authorizeHigh(String session, String... extra) {
        Map<String, String> p = ChainFixture.authParams("high", HIGH_CB, Bytes.randomToken(32), "openid profile");
        for (int i = 0; i < extra.length; i += 2) {
            p.put(extra[i], extra[i + 1]);
        }
        return f.provider.authorize(get(p).withSession(session));
    }

    @Test
    @DisplayName("SSO-T-chain-010 F-R3-1 password plus push approval is not phishing-resistant (acr:3) anywhere")
    void pushIsNotPhishingResistant() {
        Set<String> pwdPush = ChainFixture.amr(Factor.PASSWORD, Factor.PUSH);
        assertNotEquals(StepUp.ACR_PHISHING_RESISTANT, StepUp.acr(pwdPush));
        assertEquals(StepUp.ACR_MFA, StepUp.acr(pwdPush));
        assertNotEquals(StepUp.ACR_PHISHING_RESISTANT, StepUp.acr(ChainFixture.amr(Factor.PUSH)));
        Set<String> keyNoUvPlusPush = new java.util.HashSet<>(ChainFixture.amr(Factor.PUSH));
        keyNoUvPlusPush.add("hwk");
        assertNotEquals(StepUp.ACR_PHISHING_RESISTANT, StepUp.acr(keyNoUvPlusPush));
        StepUp stepUp = new StepUp(f.clock, MfaPolicy.defaults());
        assertEquals(StepUp.Reason.ACR_TOO_LOW, stepUp.evaluate(new StepUp.AuthContext(f.clock.instant(), pwdPush),
                StepUp.ACR_PHISHING_RESISTANT, true).reason());
        OidcResponse r = authorizeHigh(f.login(T1, "alice", pwdPush));
        assertEquals(OidcResponse.Kind.INTERACTION, r.kind());
        assertEquals(Interaction.Type.STEP_UP, r.interaction().type());
    }

    @Test
    @DisplayName("SSO-T-chain-011 passkey with UV satisfies acr:3 and acr/amr reach the id_token; password-only never does")
    void passkeyPropagatesAcr() {
        String s = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD, Factor.PASSKEY));
        String v = Bytes.randomToken(32);
        Map<String, String> p = ChainFixture.authParams("high", HIGH_CB, v, "openid profile");
        OidcResponse r = f.provider.authorize(get(p).withSession(s));
        assertEquals(OidcResponse.Kind.REDIRECT, r.kind(), String.valueOf(r.interaction()));
        String code = ChainFixture.query(r.redirectUrl()).get("code");
        OidcResponse t = f.provider.token(post(params("grant_type", "authorization_code", "code", code, "redirect_uri", HIGH_CB,
                "code_verifier", v)).withHeader("Authorization", basic("high", HIGH_SECRET)));
        assertEquals(200, t.status(), t.bodyJson());
        Map<String, Object> idt = ChainFixture.claims((String) t.body().get("id_token"));
        assertEquals(StepUp.ACR_PHISHING_RESISTANT, idt.get("acr"));
        assertTrue(((List<?>) idt.get("amr")).containsAll(List.of("pwd", "swk", "user")));
        String pwdOnly = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD));
        assertEquals(Interaction.Type.STEP_UP, authorizeHigh(pwdOnly).interaction().type());
        OidcResponse none = authorizeHigh(pwdOnly, "prompt", "none");
        assertEquals("interaction_required", ChainFixture.query(none.redirectUrl()).get("error"));
        String webPwd = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD, Factor.SMS_OTP));
        OidcResponse viaAcr = f.provider.authorize(get(ChainFixture.authParams("web", ChainFixture.WEB_CB, Bytes.randomToken(32), "openid"))
                .withParam("acr_values", StepUp.ACR_PHISHING_RESISTANT).withSession(webPwd));
        assertEquals(Interaction.Type.STEP_UP, viaAcr.interaction().type());
    }

    @Test
    @DisplayName("SSO-T-chain-012 F-R3-2 device flow approval honours client minAcr (password-only session cannot approve)")
    void deviceFlowHonoursMinAcr() {
        OidcResponse dev = f.provider.deviceAuthorization(post(params("scope", "openid")).withHeader("Authorization", basic("high", HIGH_SECRET)));
        assertEquals(200, dev.status(), dev.bodyJson());
        String userCode = (String) dev.body().get("user_code");
        String dc = (String) dev.body().get("device_code");
        String pwdOnly = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD));
        OidcResponse d = f.provider.deviceDecision(userCode, get(Map.of()).withSession(pwdOnly).withIp("1.1.1.1"), true);
        assertEquals("interaction_required", d.error(), d.bodyJson());
        f.clock.advance(Duration.ofSeconds(6));
        OidcResponse poll = f.provider.token(post(params("grant_type", GrantTypes.DEVICE_CODE, "device_code", dc))
                .withHeader("Authorization", basic("high", HIGH_SECRET)));
        assertEquals("authorization_pending", poll.error());
        String strong = f.login(T1, "alice", ChainFixture.amr(Factor.PASSWORD, Factor.SECURITY_KEY));
        assertEquals("approved", f.provider.deviceDecision(userCode, get(Map.of()).withSession(strong).withIp("1.1.1.1"), true)
                .body().get("status"));
        f.clock.advance(Duration.ofSeconds(6));
        OidcResponse ok = f.provider.token(post(params("grant_type", GrantTypes.DEVICE_CODE, "device_code", dc))
                .withHeader("Authorization", basic("high", HIGH_SECRET)));
        assertEquals(200, ok.status(), ok.bodyJson());
        assertEquals(StepUp.ACR_PHISHING_RESISTANT, ChainFixture.claims((String) ok.body().get("id_token")).get("acr"));
    }

    private static String authnContext(SamlIdp idp, String acr) {
        AuthnRequestInfo info = new AuthnRequestInfo("_req" + Bytes.hex(Bytes.random(8)), "https://sp.example.com",
                "https://sp.example.com/acs", null, null, false, false, List.of(), java.time.Instant.parse("2026-10-15T10:00:00Z"), false);
        String xml = new String(Base64.getDecoder().decode(idp.issueResponse(info,
                new SamlSubject("alice", "alice@example.com", java.time.Instant.parse("2026-10-15T09:59:00Z"), acr, "sess-1", Map.of()))
                .value()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<saml:AuthnContextClassRef>([^<]*)</saml:AuthnContextClassRef>").matcher(xml);
        assertTrue(m.find(), xml);
        return m.group(1);
    }

    @Test
    @DisplayName("SSO-T-chain-013 F-R3-5 naqqa acr levels map to standard SAML AuthnContextClassRef, password-only never reads as MFA")
    void samlAuthnContextMapping() {
        InMemoryServiceProviderRegistry registry = new InMemoryServiceProviderRegistry();
        registry.register(ServiceProvider.of("https://sp.example.com", List.of("https://sp.example.com/acs"), List.of()));
        SamlIdpConfig base = SamlIdpConfig.of("https://idp.naqqa.test", "https://idp.naqqa.test/sso", null,
                TestKeys.IDP.key(), TestKeys.IDP.cert(), new byte[32]);
        SamlIdp idp = new SamlIdp(base, registry, new InMemoryReplayCache(f.clock), f.clock);
        assertEquals(Saml.AC_PASSWORD_PROTECTED, authnContext(idp, StepUp.ACR_PASSWORD));
        assertEquals(Saml.AC_MFA, authnContext(idp, StepUp.ACR_MFA));
        assertEquals(Saml.AC_MFA, authnContext(idp, StepUp.ACR_PHISHING_RESISTANT));
        assertEquals(Saml.AC_PASSWORD_PROTECTED, authnContext(idp, StepUp.acr(ChainFixture.amr(Factor.PASSWORD))));
        assertEquals(Saml.AC_UNSPECIFIED, authnContext(idp, null));
        SamlIdp custom = new SamlIdp(new SamlIdpConfig(base.entityId(), base.ssoUrl(), base.sloUrl(), base.signingKey(),
                base.signingCertificate(), base.pairwiseSecret(), base.assertionLifetime(), base.clockSkew(), base.requestMaxAge(),
                Map.of("urn:example:gold", "urn:example:ac:gold"), base.limits(), base.wantAuthnRequestsSigned()),
                registry, new InMemoryReplayCache(f.clock), f.clock);
        assertEquals("urn:example:ac:gold", authnContext(custom, "urn:example:gold"));
        assertEquals(Saml.AC_PASSWORD_PROTECTED, authnContext(custom, StepUp.ACR_PASSWORD));
        assertEquals(Saml.AC_MFA, authnContext(custom, StepUp.ACR_PHISHING_RESISTANT));
    }
}
