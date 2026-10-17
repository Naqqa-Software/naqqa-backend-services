package com.naqqa.sso.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.naqqa.sso.oauth.LinkingPolicy.Outcome.CREATE_ACCOUNT;
import static com.naqqa.sso.oauth.LinkingPolicy.Outcome.LINK_REQUIRES_LOGIN;
import static com.naqqa.sso.oauth.LinkingPolicy.Outcome.LINK_TO_AUTHENTICATED_USER;
import static com.naqqa.sso.oauth.LinkingPolicy.Outcome.LINK_TO_EXISTING_ACCOUNT;
import static com.naqqa.sso.oauth.LinkingPolicy.Outcome.LOGIN_EXISTING_LINK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkingPolicyTest {
    private final LinkingPolicy policy = LinkingPolicy.defaults();
    private final ProviderConfig google = ProviderConfig.google("c", "s", "https://a/cb");
    private final ProviderConfig github = ProviderConfig.github("c", "s", "https://a/cb");
    private final ProviderConfig facebook = ProviderConfig.facebook("c", "s", "https://a/cb");
    private final ProviderConfig microsoft = ProviderConfig.microsoft("c", "s", "https://a/cb", "common", null);

    private static ExternalIdentity id(ProviderConfig p, String email, boolean verified, Map<String, Object> claims) {
        return new ExternalIdentity(p.id(), "sub-1", email, verified, "N", null, claims);
    }

    @Test
    @DisplayName("SSO-T-oauth-107 existing provider link logs in to the linked account")
    void existingLink() {
        LinkingPolicy.Decision d = policy.decide(id(google, "a@gmail.com", true, Map.of()), google, Optional.of("u1"), Optional.of("u2"), null);
        assertEquals(LOGIN_EXISTING_LINK, d.outcome());
        assertEquals("u1", d.userId());
    }

    @Test
    @DisplayName("SSO-T-oauth-108 verified gmail address links to the local account")
    void googleGmailLinks() {
        LinkingPolicy.Decision d = policy.decide(id(google, "a@gmail.com", true, Map.of()), google, Optional.empty(), Optional.of("u2"), null);
        assertEquals(LINK_TO_EXISTING_ACCOUNT, d.outcome());
        assertEquals("u2", d.userId());
    }

    @Test
    @DisplayName("SSO-T-oauth-109 unverified email never links, login to the local account is required")
    void unverifiedBlocked() {
        LinkingPolicy.Decision d = policy.decide(id(google, "a@gmail.com", false, Map.of()), google, Optional.empty(), Optional.of("u2"), null);
        assertEquals(LINK_REQUIRES_LOGIN, d.outcome());
        assertEquals("LINK_REQUIRES_LOGIN", d.code());
        assertFalse(d.emailTrusted());
    }

    @Test
    @DisplayName("SSO-T-oauth-110 verified email on a domain the provider is not trusted for does not link")
    void untrustedDomain() {
        LinkingPolicy.Decision d = policy.decide(id(google, "ceo@corp.example", true, Map.of()), google, Optional.empty(), Optional.of("u2"), null);
        assertEquals(LINK_REQUIRES_LOGIN, d.outcome());
    }

    @Test
    @DisplayName("SSO-T-oauth-111 Google Workspace hosted domain matching the email domain links")
    void hostedDomain() {
        LinkingPolicy.Decision d = policy.decide(id(google, "ceo@corp.example", true, Map.of("hd", "corp.example")), google, Optional.empty(), Optional.of("u2"), null);
        assertEquals(LINK_TO_EXISTING_ACCOUNT, d.outcome());
    }

    @Test
    @DisplayName("SSO-T-oauth-112 Google hd for a different domain than the email does not link")
    void hostedDomainMismatch() {
        LinkingPolicy.Decision d = policy.decide(id(google, "ceo@corp.example", true, Map.of("hd", "evil.example")), google, Optional.empty(), Optional.of("u2"), null);
        assertEquals(LINK_REQUIRES_LOGIN, d.outcome());
    }

    @Test
    @DisplayName("SSO-T-oauth-113 provider-level trust flag allows linking any verified email (GitHub)")
    void providerTrusted() {
        LinkingPolicy.Decision d = policy.decide(id(github, "x@corp.example", true, Map.of()), github, Optional.empty(), Optional.of("u3"), null);
        assertEquals(LINK_TO_EXISTING_ACCOUNT, d.outcome());
    }

    @Test
    @DisplayName("SSO-T-oauth-114 untrusted provider never links even with verified flag (Microsoft, Facebook)")
    void untrustedProvider() {
        assertEquals(LINK_REQUIRES_LOGIN, policy.decide(id(microsoft, "x@corp.example", true, Map.of()), microsoft, Optional.empty(), Optional.of("u3"), null).outcome());
        assertEquals(LINK_REQUIRES_LOGIN, policy.decide(id(facebook, "x@corp.example", true, Map.of()), facebook, Optional.empty(), Optional.of("u3"), null).outcome());
    }

    @Test
    @DisplayName("SSO-T-oauth-115 missing email creates a new account without trusting any email")
    void noEmail() {
        LinkingPolicy.Decision d = policy.decide(id(github, null, false, Map.of()), github, Optional.empty(), Optional.empty(), null);
        assertEquals(CREATE_ACCOUNT, d.outcome());
        assertFalse(d.emailTrusted());
    }

    @Test
    @DisplayName("SSO-T-oauth-116 no local account with that email creates an account, email trust follows verification")
    void createAccount() {
        LinkingPolicy.Decision ok = policy.decide(id(google, "new@gmail.com", true, Map.of()), google, Optional.empty(), Optional.empty(), null);
        assertEquals(CREATE_ACCOUNT, ok.outcome());
        assertTrue(ok.emailTrusted());
        LinkingPolicy.Decision no = policy.decide(id(google, "new@gmail.com", false, Map.of()), google, Optional.empty(), Optional.empty(), null);
        assertFalse(no.emailTrusted());
    }

    @Test
    @DisplayName("SSO-T-oauth-117 already logged-in user links explicitly without email match")
    void authenticatedLink() {
        LinkingPolicy.Decision d = policy.decide(id(facebook, null, false, Map.of()), facebook, Optional.empty(), Optional.of("other"), "me");
        assertEquals(LINK_TO_AUTHENTICATED_USER, d.outcome());
        assertEquals("me", d.userId());
    }

    @Test
    @DisplayName("SSO-T-oauth-118 auto-link can be disabled globally")
    void autoLinkDisabled() {
        LinkingPolicy off = new LinkingPolicy(false);
        LinkingPolicy.Decision d = off.decide(id(google, "a@gmail.com", true, Map.of()), google, Optional.empty(), Optional.of("u2"), null);
        assertEquals(LINK_REQUIRES_LOGIN, d.outcome());
    }

    @Test
    @DisplayName("SSO-T-oauth-119 domain trust is exact, subdomain and lookalike do not match")
    void domainExact() {
        assertFalse(policy.emailTrusted(id(google, "a@evilgmail.com", true, Map.of()), google));
        assertFalse(policy.emailTrusted(id(google, "a@sub.gmail.com", true, Map.of()), google));
        assertTrue(policy.emailTrusted(id(google, "a@GMAIL.com".toLowerCase(), true, Map.of()), google));
    }

    @Test
    @DisplayName("SSO-T-oauth-120 identity from another provider than the config is rejected")
    void providerMismatch() {
        com.naqqa.sso.SsoException e = org.junit.jupiter.api.Assertions.assertThrows(com.naqqa.sso.SsoException.class,
                () -> policy.decide(id(github, "a@b.example", true, Map.of()), google, Optional.empty(), Optional.empty(), null));
        assertEquals("oauth.policy_provider_mismatch", e.code());
    }

    @Test
    @DisplayName("SSO-T-oauth-121 enterprise tenant domain trust list links only its own domains")
    void enterpriseDomains() {
        ProviderConfig ent = ProviderConfig.enterprise("acme", "https://idp.acme.example", "c", "s", "https://a/cb", Set.of("acme.example"));
        ExternalIdentity ok = new ExternalIdentity(ent.id(), "s", "bob@acme.example", true, "Bob", null, Map.of());
        ExternalIdentity other = new ExternalIdentity(ent.id(), "s", "bob@other.example", true, "Bob", null, Map.of());
        assertEquals(LINK_TO_EXISTING_ACCOUNT, policy.decide(ok, ent, Optional.empty(), Optional.of("u"), null).outcome());
        assertEquals(LINK_REQUIRES_LOGIN, policy.decide(other, ent, Optional.empty(), Optional.of("u"), null).outcome());
    }
}
