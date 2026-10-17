package com.naqqa.auth.sso;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.entity.authorities.RoleEntity;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.service.security.RefreshTokenManager;
import com.naqqa.auth.sso.flow.AccountLinkService;
import com.naqqa.auth.sso.flow.MfaTokenService;
import com.naqqa.auth.sso.flow.SsoAudit;
import com.naqqa.auth.sso.flow.SsoCredentialRevoker;
import com.naqqa.auth.sso.flow.SsoSessionManager;
import com.naqqa.auth.sso.ldap.DirectoryLogin;
import com.naqqa.auth.sso.oidc.BackchannelLogoutDispatcher;
import com.naqqa.auth.sso.oidc.MongoOidcStore;
import com.naqqa.auth.sso.saml.SamlSpIdpStore;
import com.naqqa.auth.sso.social.SocialLoginController;
import com.naqqa.auth.sso.store.MongoMagicLinkStore;
import com.naqqa.auth.sso.store.MongoOtpStore;
import com.naqqa.auth.sso.store.MongoPushApprovalStore;
import com.naqqa.auth.sso.support.SsoTicketStore;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.auth.support.TestSamlIdp;
import com.naqqa.sso.oidc.GrantRecord;
import com.naqqa.sso.oidc.OidcProvider;
import com.naqqa.sso.saml.Saml;
import com.naqqa.sso.saml.SamlBindings;
import com.naqqa.sso.saml.xml.XmlParser;
import com.naqqa.sso.session.SessionService;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound13AuthTest extends OidcTestBase {

    static final TestSamlIdp SAML = new TestSamlIdp("https://idp.r13.example.org/saml");
    static final TestSamlIdp ROGUE = new TestSamlIdp("https://rogue.r13.example.org/saml");
    static final String FRONT = "http://localhost:4200";
    static final String ADMIN = "/api/sso/admin";

    @Autowired
    SamlSpIdpStore idps;

    @Autowired
    MongoOidcStore oidcStore;

    @Autowired
    ApplicationContext context;

    String samlTenant(String tag, boolean authoritative) {
        role("USER", "profile:read");
        String id = tag + "-" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
        registeredTenant(id);
        String domain = id + ".example.org";
        idps.save(new SamlSpIdpStore.IdpSettings(id, true, true, "Acme", SAML.entityId, "https://idp.r13.example.org/sso", List.of(SAML.pem()),
                Saml.NAMEID_PERSISTENT, "email", "name", "groups", List.of(domain), authoritative ? List.of(domain) : List.of(), true, Map.of(),
                null, false, List.of(Saml.AC_MFA), false, true, false, false, clock.instant(), "test"));
        return id;
    }

    MvcResult samlSignIn(Browser b, TestSamlIdp idp, String tenant, String subject, String email) {
        MvcResult r = b.perform(MockMvcRequestBuilders.get("/saml2/sp/" + tenant + "/login").param("relay", "/p"));
        assertEquals(302, status(r), content(r));
        String raw = URI.create(location(r)).getRawQuery();
        String requestId = new XmlParser().parse(SamlBindings.decodeRedirect(raw, "SAMLRequest", SamlBindings.MAX_INFLATED).xml())
                .root().attribute("ID");
        String sp = "http://localhost/saml2/sp/" + tenant + "/metadata";
        String acs = "http://localhost/saml2/sp/" + tenant + "/acs";
        String response = idp.response(idp.spec(sp, acs, requestId, subject, email, clock.instant()));
        return b.perform(MockMvcRequestBuilders.post("/saml2/sp/" + tenant + "/acs").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Origin", "https://idp.example.org").param("SAMLResponse", response));
    }

    MvcResult socialLogin(Browser b, String subject, String email, String extra) {
        MvcResult r = get(b, "/oauth2/authorization/fakeidp?redirect=" + enc("/home") + (extra == null ? "" : "&" + extra));
        assertEquals(302, status(r), content(r));
        assertNotNull(b.cookie(SocialLoginController.BINDING_COOKIE));
        Map<String, String> q = query(location(r));
        Map<String, Object> claims = new LinkedHashMap<>();
        long now = clock.instant().getEpochSecond();
        claims.put("sub", subject);
        claims.put("aud", "fakeidp-client");
        claims.put("iat", now);
        claims.put("exp", now + 300);
        claims.put("nonce", q.get("nonce"));
        claims.put("email", email);
        claims.put("email_verified", true);
        String code = Bytes.randomToken(16);
        IDP.issue(code, claims);
        return get(b, "/login/oauth2/code/fakeidp?state=" + enc(q.get("state")) + "&code=" + enc(code));
    }

    long links(UserEntity u) {
        return mongo.count(new Query(Criteria.where("userId").is(String.valueOf(u.getId()))), AccountLinkService.COLLECTION);
    }

    List<String> reasons(String action) {
        return mongo.find(new Query(Criteria.where("action").is(action)), Document.class, "sso_audit").stream()
                .flatMap(d -> d.getList("attributes", Document.class, List.of()).stream())
                .filter(a -> "reason".equals(a.getString("k"))).map(a -> a.getString("v")).toList();
    }

    long notices(String email, String event) {
        return transport.to(email).stream().filter(m -> event.equals(m.vars().get("event"))).count();
    }

    UserEntity tenantAdmin(String tenant) {
        UserEntity a = createUser(uniqueEmail("r13-tadmin"), passwordService.hash(PASSWORD), role("R13_TENANT_ADMIN", "SSO_ADMIN_CLIENTS"));
        mongo.updateFirst(new Query(Criteria.where("_id").is(tenant)), new Update().addToSet("admins", String.valueOf(a.getId())), "sso_tenants");
        return a;
    }

    @Test
    @DisplayName("SSO-T-auth-685 F-A13-AUTH-1 links of a tenant IdP do not outlive its platform grant: a tenant admin cannot delete an IdP with allowed domains, a linked sign-in needs an allowed domain, and removing the IdP removes its links")
    void idpReplacementDoesNotInheritLinks() {
        String t = samlTenant("r13-swap", false);
        String domain = t + ".example.org";
        String subject = "swap-" + Bytes.randomToken(6);
        String email = "member-" + Bytes.randomToken(4).toLowerCase() + "@" + domain;
        Browser first = browser();
        MvcResult jit = samlSignIn(first, SAML, t, subject, email);
        assertEquals(FRONT + "/p", location(jit), content(jit));
        UserEntity member = userRepository.findByEmail(email).orElseThrow();
        assertEquals(1, links(member));

        Browser ta = loggedIn(tenantAdmin(t).getEmail());
        Browser pa = loggedIn(createUser(uniqueEmail("r13-padmin"), passwordService.hash(PASSWORD),
                role("R13_PLATFORM_ADMIN", "SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS")).getEmail());
        String path = ADMIN + "/tenants/" + t + "/saml-idp";
        MvcResult del = ta.delete(path);
        assertEquals(403, status(del), content(del));
        assertEquals("PLATFORM_ONLY", json(del).get("code").asText());

        MvcResult revoke = pa.put(path, Map.of("allowedDomains", List.of(), "authoritativeDomains", List.of()));
        assertEquals(200, status(revoke), content(revoke));
        MvcResult swap = ta.put(path, Map.of("entityId", ROGUE.entityId, "signingCertificates", List.of(ROGUE.pem())));
        assertEquals(200, status(swap), content(swap));
        Browser rogue = browser();
        MvcResult r = samlSignIn(rogue, ROGUE, t, subject, email);
        assertEquals(FRONT + "/login?error=DOMAIN_NOT_ALLOWED", location(r));
        assertFalse(rogue.cookies().containsKey("access_token"));

        assertEquals(204, status(pa.delete(path)));
        assertEquals(0, links(member));
    }

    @Test
    @DisplayName("SSO-T-auth-691 F-A13-AUTH-6 links of a tenant IdP do not survive the platform revoking its last grant: a tenant admin swapping the trust anchors meanwhile does not inherit them when the domains are granted again")
    void regrantAfterAnchorSwapDoesNotReviveLinks() {
        String t = samlTenant("r13-regrant", false);
        String domain = t + ".example.org";
        String subject = "regrant-" + Bytes.randomToken(6);
        String email = "member-" + Bytes.randomToken(4).toLowerCase() + "@" + domain;
        assertEquals(FRONT + "/p", location(samlSignIn(browser(), SAML, t, subject, email)));
        UserEntity member = userRepository.findByEmail(email).orElseThrow();
        assertEquals(1, links(member));

        Browser ta = loggedIn(tenantAdmin(t).getEmail());
        Browser pa = loggedIn(createUser(uniqueEmail("r13-padmin"), passwordService.hash(PASSWORD),
                role("R13_PLATFORM_ADMIN", "SSO_ADMIN_TENANTS", "SSO_ADMIN_CLIENTS")).getEmail());
        String path = ADMIN + "/tenants/" + t + "/saml-idp";
        assertEquals(200, status(pa.put(path, Map.of("allowedDomains", List.of(), "authoritativeDomains", List.of()))));
        assertEquals(0, links(member));
        assertEquals(200, status(ta.put(path, Map.of("entityId", ROGUE.entityId, "signingCertificates", List.of(ROGUE.pem())))));
        assertEquals(200, status(pa.put(path, Map.of("allowedDomains", List.of(domain)))));

        Browser rogue = browser();
        MvcResult r = samlSignIn(rogue, ROGUE, t, subject, email);
        assertTrue(location(r).startsWith(FRONT + "/login?error="), location(r));
        assertFalse(rogue.cookies().containsKey("access_token"));
        assertEquals(0, links(member));
    }

    @Test
    @DisplayName("SSO-T-auth-692 F-A13-AUTH-7 adoption by email never touches a disabled account: social auto-link, SAML and directory adoption write no link, keep the local password and notify nobody")
    void adoptionSkipsDisabledAccounts() {
        UserEntity social = user(uniqueEmail("r13-off-social"));
        mongo.updateFirst(new Query(Criteria.where("_id").is(social.getId())), new Update().set("enabled", false), UserEntity.class);
        Browser s = browser();
        MvcResult sr = socialLogin(s, "off-" + Bytes.randomToken(6), social.getEmail(), null);
        assertTrue(location(sr).endsWith("error=ACCOUNT_BLOCKED"), location(sr));
        assertFalse(s.cookies().containsKey("access_token"));
        assertEquals(0, links(social));
        assertEquals(0, notices(social.getEmail(), "account_linked"));

        String t = samlTenant("r13-off", true);
        UserEntity saml = user("off-" + Bytes.randomToken(4).toLowerCase() + "@" + t + ".example.org");
        mongo.updateFirst(new Query(Criteria.where("_id").is(saml.getId())), new Update().set("enabled", false), UserEntity.class);
        Browser b = browser();
        assertEquals(FRONT + "/login?error=ACCOUNT_BLOCKED", location(samlSignIn(b, SAML, t, "off-" + Bytes.randomToken(6), saml.getEmail())));
        assertFalse(b.cookies().containsKey("access_token"));
        assertEquals(0, links(saml));
        assertEquals(0, notices(saml.getEmail(), "account_linked"));

        UserEntity dir = user(uniqueEmail("r13-off-dir"));
        mongo.updateFirst(new Query(Criteria.where("_id").is(dir.getId())), new Update().set("enabled", false), UserEntity.class);
        DIRECTORY.add(dir.getEmail(), "dir-pass", "uid=" + Bytes.randomToken(6) + ",ou=people,dc=corp", Map.of("mail", List.of(dir.getEmail())));
        Browser d = browser();
        d.clientId("corp-app");
        MvcResult dr = login(d, dir.getEmail(), "dir-pass");
        assertTrue(status(dr) >= 400, content(dr));
        assertFalse(d.cookies().containsKey("access_token"));
        assertEquals(0, mongo.getCollection(DirectoryLogin.COLLECTION).countDocuments(new Document("userId", String.valueOf(dir.getId()))));
        assertNotNull(userRepository.findById(dir.getId()).orElseThrow().getPassword());
        assertEquals(0, notices(dir.getEmail(), "account_linked"));
    }

    @Test
    @DisplayName("SSO-T-auth-693 F-CORE-30 a refresh landing between the grant listing and the revocation marker of a credential change cannot keep its access token or its grant live")
    void credentialRevocationRereadsGrantAfterMarker() {
        String userId = "r13-" + Bytes.randomToken(8);
        String grantId = "g-" + Bytes.randomToken(12);
        oidcStore.saveGrant(new GrantRecord(grantId, "default", "client-r13", userId, Set.of("openid", "offline_access"), null, "1", Set.of("pwd"),
                clock.instant(), null, null, null, clock.instant().plusSeconds(86400), Map.of("jti-1", clock.instant().plusSeconds(600)), false));
        MongoOidcStore racing = new MongoOidcStore(mongo, clock, Duration.ofDays(30)) {
            @Override
            public List<GrantRecord> grantsForUser(String user) {
                List<GrantRecord> listed = super.grantsForUser(user);
                for (GrantRecord g : listed) {
                    oidcStore.saveGrant(g.withAccessToken("jti-2", clock.instant().plusSeconds(600), clock.instant()));
                }
                return listed;
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("racing", racing));
        SsoCredentialRevoker revoker = new SsoCredentialRevoker(context.getBean(SsoSessionManager.class), context.getBean(RefreshTokenManager.class),
                context.getBean(SsoTicketStore.class), context.getBean(MfaTokenService.class), context.getBean(MongoMagicLinkStore.class),
                context.getBean(MongoOtpStore.class), context.getBean(MongoPushApprovalStore.class), context.getBeanProvider(OidcProvider.class),
                context.getBeanProvider(BackchannelLogoutDispatcher.class), beans.getBeanProvider(MongoOidcStore.class),
                context.getBean(SessionService.class), context.getBean(SsoAudit.class), context.getBeanProvider(PasswordResetRepository.class));
        assertEquals(1, revoker.revokeOidcGrants(userId));
        assertTrue(oidcStore.isRevoked("grant:" + grantId));
        assertTrue(oidcStore.isRevoked("at:jti-1"));
        assertTrue(oidcStore.isRevoked("at:jti-2"));
        assertTrue(oidcStore.findGrant(grantId).orElseThrow().revoked());
    }

    @Test
    @DisplayName("SSO-T-auth-686 F-A13-AUTH-2 a SAML linked account that the directory adopts later stops signing in through the tenant IdP")
    void samlLinkThenDirectoryAdoption() {
        String t = samlTenant("r13-dir", true);
        String email = "dir-" + Bytes.randomToken(4).toLowerCase() + "@" + t + ".example.org";
        String subject = "dir-" + Bytes.randomToken(6);
        assertEquals(FRONT + "/p", location(samlSignIn(browser(), SAML, t, subject, email)));
        UserEntity u = userRepository.findByEmail(email).orElseThrow();
        assertTrue(u.isEmailVerified());
        DIRECTORY.add(email, "dir-pass", "uid=" + Bytes.randomToken(6) + ",ou=people,dc=corp", Map.of("mail", List.of(email)));
        Browser b = browser();
        b.clientId("corp-app");
        MvcResult ldap = login(b, email, "dir-pass");
        assertEquals(200, status(ldap), content(ldap));
        assertEquals(1, mongo.getCollection(DirectoryLogin.COLLECTION).countDocuments(new Document("userId", String.valueOf(u.getId()))));

        Browser again = browser();
        assertEquals(FRONT + "/login?error=LINK_REQUIRES_LOGIN", location(samlSignIn(again, SAML, t, subject, email)));
        assertFalse(again.cookies().containsKey("access_token"));
        assertTrue(reasons("saml.sp.login").contains("directory_managed"), reasons("saml.sp.login").toString());
    }

    @Test
    @DisplayName("SSO-T-auth-687 F-A13-AUTH-3 social sign-in never auto-links an administrative or directory-managed account, and a blocked account cannot complete a social link")
    void socialAutoLinkTargets() {
        UserEntity admin = createUser(uniqueEmail("r13-soc-admin"), passwordService.hash(PASSWORD), role("R13_SOC_ADMIN", "SSO_ADMIN_USERS"));
        Browser a = browser();
        MvcResult r = socialLogin(a, "sa-" + Bytes.randomToken(6), admin.getEmail(), null);
        assertTrue(location(r).endsWith("error=LINK_REQUIRES_LOGIN"), location(r));
        assertFalse(a.cookies().containsKey("access_token"));
        assertEquals(0, links(admin));

        UserEntity managed = user(uniqueEmail("r13-soc-dir"));
        DIRECTORY.add(managed.getEmail(), "dir-pass", "uid=" + Bytes.randomToken(6) + ",ou=people,dc=corp", Map.of("mail", List.of(managed.getEmail())));
        Browser d = browser();
        d.clientId("corp-app");
        assertEquals(200, status(login(d, managed.getEmail(), "dir-pass")));
        Browser s = browser();
        MvcResult dr = socialLogin(s, "sd-" + Bytes.randomToken(6), managed.getEmail(), null);
        assertTrue(location(dr).endsWith("error=LINK_REQUIRES_LOGIN"), location(dr));
        assertEquals(0, links(managed));

        UserEntity blocked = user(uniqueEmail("r13-soc-blocked"));
        Browser bb = loggedIn(blocked.getEmail());
        MvcResult intent = bb.post(BASE + "/account/links/fakeidp/start", null);
        assertEquals(200, status(intent), content(intent));
        mongo.updateFirst(new Query(Criteria.where("_id").is(blocked.getId())), new Update().set("blocked", true), UserEntity.class);
        MvcResult br = socialLogin(bb, "sb-" + Bytes.randomToken(6), "other-" + Bytes.randomToken(4) + "@corp.test",
                "link=" + enc(json(intent).get("linkIntent").asText()));
        assertTrue(location(br).contains("error="), location(br));
        assertEquals(0, links(blocked));
    }

    @Test
    @DisplayName("SSO-T-auth-688 F-A13-AUTH-4 a directory group mapped to an administrative role grants nothing, as for SAML group mappings")
    void directoryNeverGrantsAdministrativeRoles() {
        role("SUPPORT", "SSO_ADMIN_USERS");
        try {
            String email = uniqueEmail("r13-ldap-admin");
            DIRECTORY.add(email, "dir-pass", "uid=" + Bytes.randomToken(6) + ",ou=people,dc=corp",
                    Map.of("mail", List.of(email), "memberOf", List.of("cn=support,ou=groups,dc=corp")));
            Browser b = browser();
            b.clientId("corp-app");
            MvcResult r = login(b, email, "dir-pass");
            assertEquals(200, status(r), content(r));
            Set<String> roles = userRepository.findByEmail(email).orElseThrow().getRoles().stream().map(RoleEntity::getName)
                    .collect(Collectors.toSet());
            assertFalse(roles.contains("SUPPORT"), roles.toString());
        } finally {
            role("SUPPORT", "profile:read");
        }
    }

    @Test
    @DisplayName("SSO-T-auth-689 F-A13-AUTH-5 every sign-in method added to or removed from an existing account by federation is notified: social link and auto-link, SAML adoption, directory adoption, unlink")
    void federationNotices() {
        UserEntity linker = user(uniqueEmail("r13-n-link"));
        Browser b = loggedIn(linker.getEmail());
        MvcResult intent = b.post(BASE + "/account/links/fakeidp/start", null);
        assertEquals(200, status(intent), content(intent));
        MvcResult linked = socialLogin(b, "nl-" + Bytes.randomToken(6), "n-" + Bytes.randomToken(4) + "@corp.test",
                "link=" + enc(json(intent).get("linkIntent").asText()));
        assertEquals(FRONT + "/home?linked=fakeidp", location(linked));
        assertEquals(1, notices(linker.getEmail(), "account_linked"));
        assertEquals(204, status(b.delete(BASE + "/account/links/fakeidp")));
        assertEquals(1, notices(linker.getEmail(), "factor_removed"));

        UserEntity auto = user(uniqueEmail("r13-n-auto"));
        assertEquals(FRONT + "/home", location(socialLogin(browser(), "na-" + Bytes.randomToken(6), auto.getEmail(), null)));
        assertEquals(1, notices(auto.getEmail(), "account_linked"));

        String t = samlTenant("r13-note", true);
        UserEntity adopted = user("adopt-" + Bytes.randomToken(4).toLowerCase() + "@" + t + ".example.org");
        assertEquals(FRONT + "/p", location(samlSignIn(browser(), SAML, t, "na-" + Bytes.randomToken(6), adopted.getEmail())));
        assertEquals(1, links(adopted));
        assertEquals(1, notices(adopted.getEmail(), "account_linked"));

        UserEntity dir = user(uniqueEmail("r13-n-dir"));
        DIRECTORY.add(dir.getEmail(), "dir-pass", "uid=" + Bytes.randomToken(6) + ",ou=people,dc=corp", Map.of("mail", List.of(dir.getEmail())));
        Browser d = browser();
        d.clientId("corp-app");
        assertEquals(200, status(login(d, dir.getEmail(), "dir-pass")));
        assertEquals(1, notices(dir.getEmail(), "account_linked"));
    }

    @Test
    @DisplayName("SSO-T-auth-690 federation matrix (federation.md): later changes to a federated account, per upstream type: factor enrolled, account disabled, administrative role granted")
    void federationLaterChanges() {
        UserEntity social = user(uniqueEmail("r13-m-social"));
        String socialSubject = "ms-" + Bytes.randomToken(6);
        assertEquals(FRONT + "/home", location(socialLogin(browser(), socialSubject, social.getEmail(), null)));
        social.getRoles().add(role("R13_LATER_ADMIN", "SSO_ADMIN_USERS"));
        userRepository.save(social);
        String adminLogin = location(socialLogin(browser(), socialSubject, social.getEmail(), null));
        assertTrue(adminLogin.startsWith(FRONT + "/login#mfaToken="), adminLogin);
        mongo.updateFirst(new Query(Criteria.where("_id").is(social.getId())), new Update().set("enabled", false), UserEntity.class);
        assertTrue(location(socialLogin(browser(), socialSubject, social.getEmail(), null)).endsWith("error=ACCOUNT_BLOCKED"));

        String t = samlTenant("r13-m-saml", true);
        String samlEmail = "m-" + Bytes.randomToken(4).toLowerCase() + "@" + t + ".example.org";
        String samlSubject = "mm-" + Bytes.randomToken(6);
        assertEquals(FRONT + "/p", location(samlSignIn(browser(), SAML, t, samlSubject, samlEmail)));
        UserEntity saml = userRepository.findByEmail(samlEmail).orElseThrow();
        mongo.updateFirst(new Query(Criteria.where("_id").is(saml.getId())), new Update().set("enabled", false), UserEntity.class);
        assertEquals(FRONT + "/login?error=ACCOUNT_BLOCKED", location(samlSignIn(browser(), SAML, t, samlSubject, samlEmail)));

        String dirEmail = uniqueEmail("r13-m-dir");
        DIRECTORY.add(dirEmail, "dir-pass", "uid=" + Bytes.randomToken(6) + ",ou=people,dc=corp", Map.of("mail", List.of(dirEmail)));
        Browser d = browser();
        d.clientId("corp-app");
        MvcResult first = login(d, dirEmail, "dir-pass");
        assertEquals("ok", json(first).get("status").asText(), content(first));
        UserEntity dir = userRepository.findByEmail(dirEmail).orElseThrow();
        dir.getRoles().add(role("R13_LATER_ADMIN", "SSO_ADMIN_USERS"));
        userRepository.save(dir);
        Browser d2 = browser();
        d2.clientId("corp-app");
        assertEquals(200, status(login(d2, dirEmail, "dir-pass")));
        assertFalse(userRepository.findByEmail(dirEmail).orElseThrow().getRoles().stream().anyMatch(r -> "R13_LATER_ADMIN".equals(r.getName())));
        enrollTotp(d);
        Browser d3 = browser();
        d3.clientId("corp-app");
        MvcResult mfa = login(d3, dirEmail, "dir-pass");
        assertEquals("mfa_required", json(mfa).get("status").asText(), content(mfa));
        assertTrue(json(mfa).get("methods").toString().contains("totp"), content(mfa));
        mongo.updateFirst(new Query(Criteria.where("_id").is(dir.getId())), new Update().set("enabled", false), UserEntity.class);
        Browser d4 = browser();
        d4.clientId("corp-app");
        MvcResult off = login(d4, dirEmail, "dir-pass");
        assertEquals(403, status(off), content(off));
        assertEquals("ACCOUNT_BLOCKED", json(off).get("code").asText());
    }

    @Test
    @DisplayName("SSO-T-auth-696 F-CONF-1 a social sign-in of an administrative account follows the configured MFA role policy, not the administrative authority (federation.md)")
    void socialAdminSecondFactor() {
        UserEntity admin = user(uniqueEmail("r13-conf-admin"));
        String subject = "ca-" + Bytes.randomToken(6);
        Browser b = browser();
        assertEquals(FRONT + "/home", location(socialLogin(b, subject, admin.getEmail(), null)));
        admin.getRoles().add(role("R13_CONF_ADMIN", "SSO_ADMIN_USERS"));
        userRepository.save(admin);
        assertEquals(FRONT + "/home", location(socialLogin(b, subject, admin.getEmail(), null)));
        admin.getRoles().add(role("SUPPORT"));
        userRepository.save(admin);
        String again = location(socialLogin(b, subject, admin.getEmail(), null));
        assertTrue(again.startsWith(FRONT + "/login#mfaToken="), again);
    }
}
