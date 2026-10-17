package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.auth.dto.auth.ForgotPasswordRequest;
import com.naqqa.auth.entity.auth.PasswordResetEntity;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.repository.redis.PasswordResetRepository;
import com.naqqa.auth.service.auth.AuthService;
import com.naqqa.auth.sso.flow.AccountLinkService;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.Jwts;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.auth.support.SoftAuthenticator;
import com.naqqa.sso.tokens.ApiTokenEnv;
import com.naqqa.sso.tokens.ApiTokenKind;
import com.naqqa.sso.tokens.ApiTokenService;
import com.naqqa.sso.util.Bytes;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityControlMatrixTest extends OidcTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();

    enum Credential {
        CURRENT, SESSION, REFRESH, OFFLINE_GRANT, REMEMBERED_DEVICE, AUTHORIZATION_CODE, DEVICE_CODE, MFA_TOKEN, PUSH_APPROVAL,
        PENDING_OTP, MAGIC_LINK, RESET_TOKEN
    }

    enum Event {
        NONE("PPPPPPPPPPPP"),
        EMAIL_VERIFIED("RRRRRRRRRRRR"),
        EMAIL_CHANGED("RRRRRRRRRRRR"),
        PASSWORD_RESET("RRRRRRRRRRRR"),
        SCIM_PASSWORD_SET("RRRRRRRRRRRR"),
        FACTOR_ADDED("PRRRRRRRRRRR"),
        FACTOR_REMOVED("PRRRRRRRRRRR"),
        RECOVERY_REGENERATED("PPPPPPPPPPPP"),
        ADMIN_FACTOR_RESET("RRRRRRRRRRRR"),
        LINK_ADDED("PPPPPPPPPPPP"),
        LINK_REMOVED("PRRRRRRRRRRR"),
        DIRECTORY_ADOPTION("RRRRRRRRRRRR"),
        IDP_LINKS_REMOVED("RRRRRRRRRRRR"),
        ADMIN_ROLE_GRANTED("PPPPPPPPPPPP"),
        ROLE_REMOVED("PPPPPPPPPPPP"),
        SESSIONS_REVOKED_OTHERS("PRRPRRRPPPPP"),
        ADMIN_SESSIONS_REVOKED("RRRRRRRRRRRR"),
        DISABLED("RRRRRRRRRRRR"),
        SCIM_DEACTIVATED("RRRRRRRRRRRR"),
        ERASED("RRRRRRRRRRRR");

        final String kept;

        Event(String kept) {
            this.kept = kept;
        }

        boolean keeps(Credential c) {
            return kept.charAt(c.ordinal()) == 'P';
        }
    }

    @Autowired
    ApiTokenService apiTokens;
    @Autowired
    AccountLinkService links;
    @Autowired
    PasswordResetRepository passwordResetRepository;
    @Autowired
    AuthService authService;

    static final String ADMIN = "/api/sso/admin";

    final class Account {
        UserEntity user;
        String email;
        String scimId;
        String totp;
        Browser current;
        Browser other;
        String offlineRefresh;
        String magicToken;
        String phone;
        String smsCode;
        Browser otpPending;
        Browser mfaPending;
        String mfaToken;
        Browser pushPending;
        String pushToken;
        String pushId;
        String code;
        Pkce2 codePkce;
        String deviceCode;
        String resetToken;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Event.class)
    @DisplayName("SSO-T-auth-772 identity control matrix: every event that changes who controls an account or what it holds (email verified or changed, password reset or set by SCIM, factor added, removed, regenerated or reset by an admin, federation link added or removed, directory adoption, IdP links removed, role granted or removed, sessions revoked by the user or an admin, disable, SCIM deactivation, erasure) ends or keeps each credential of the account (acting session, other session, refresh family, OIDC offline grant, remembered device, authorization code and approved device code in flight, MFA token in flight, approved push, pending OTP, magic link, password reset token) as identity-lifecycle.md states (F-CONF-24, F-CONF-25, F-CONF-27)")
    void eventEndsOrKeepsEachCredential(Event event) {
        Account a = account(event);
        run(event, a);
        Map<Credential, Boolean> alive = new EnumMap<>(Credential.class);
        alive.put(Credential.CURRENT, status(a.current.get(BASE + "/test/me")) == 200);
        alive.put(Credential.SESSION, status(a.other.get(BASE + "/test/me")) == 200);
        alive.put(Credential.REFRESH, status(a.other.post(BASE + "/refresh", null)) == 200);
        alive.put(Credential.OFFLINE_GRANT, status(form(browser(), "/oauth2/token", Map.of("grant_type", "refresh_token",
                "refresh_token", a.offlineRefresh, "client_id", "app-a"), null)) == 200);
        alive.put(Credential.REMEMBERED_DEVICE, mongo.getCollection("sso_tickets").countDocuments(new Document("kind", "remembered-device")
                .append("userId", String.valueOf(a.user.getId()))) > 0);
        alive.put(Credential.AUTHORIZATION_CODE, status(form(browser(), "/oauth2/token", Map.of("grant_type", "authorization_code",
                "code", a.code, "redirect_uri", APP_A_REDIRECT, "code_verifier", a.codePkce.verifier(), "client_id", "app-a"), null)) == 200);
        clock.advance(Duration.ofSeconds(6));
        alive.put(Credential.DEVICE_CODE, status(form(browser(), "/oauth2/token", Map.of("grant_type",
                "urn:ietf:params:oauth:grant-type:device_code", "device_code", a.deviceCode, "client_id", "tv"), null)) == 200);
        nextTotpStep();
        alive.put(Credential.MFA_TOKEN, status(a.mfaPending.post(BASE + "/mfa/verify", Map.of("mfaToken", a.mfaToken, "method", "totp",
                "code", totpCode(a.totp)))) == 200);
        alive.put(Credential.PUSH_APPROVAL, status(a.pushPending.post(BASE + "/mfa/verify", Map.of("mfaToken", a.pushToken, "method", "push",
                "code", a.pushId))) == 200);
        alive.put(Credential.PENDING_OTP, status(a.otpPending.post(BASE + "/otp/verify", Map.of("channel", "sms", "destination", a.phone,
                "code", a.smsCode))) == 200);
        alive.put(Credential.MAGIC_LINK, status(a.other.post(BASE + "/magic-link/consume", Map.of("token", a.magicToken))) == 200);
        alive.put(Credential.RESET_TOKEN, status(browser().post(BASE + "/change-password", Map.of("email", a.email, "uuid", a.resetToken,
                "password", "Quiet-Harbor-Lantern-42!"))) == 200);
        List<String> problems = new ArrayList<>();
        for (Credential c : Credential.values()) {
            if (alive.get(c) != event.keeps(c)) {
                problems.add(c + (event.keeps(c) ? " was ended" : " survived"));
            }
        }
        assertTrue(problems.isEmpty(), event + ": " + problems);
    }

    private Account account(Event event) {
        role("USER", "profile:read");
        Account a = new Account();
        a.email = uniqueEmail("matrix-" + event.name().toLowerCase().replace('_', '-'));
        if (event == Event.EMAIL_CHANGED || event == Event.SCIM_PASSWORD_SET || event == Event.SCIM_DEACTIVATED) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
            body.put("userName", a.email);
            body.put("emails", List.of(Map.of("value", a.email, "primary", true)));
            body.put("active", true);
            MvcResult r = scim().post("/scim/v2/Users", body);
            assertEquals(201, status(r), content(r));
            a.scimId = json(r).get("id").asText();
            UserEntity u = userRepository.findByEmail(a.email).orElseThrow();
            u.setPassword(passwordService.hash(PASSWORD));
            u.setEmailVerified(true);
            userRepository.save(u);
        } else {
            user(a.email);
        }
        a.current = loggedIn(a.email);
        a.totp = enrollTotp(a.current).secret();
        if (event == Event.EMAIL_VERIFIED) {
            mongo.getCollection("users").updateOne(new Document("email", a.email), new Document("$set", new Document("emailVerified", false)));
        }
        a.phone = "+4073" + ThreadLocalRandom.current().nextInt(1000000, 9999999);
        mongo.getCollection("users").updateOne(new Document("email", a.email), new Document("$set", new Document("phoneNumber", a.phone)));
        a.user = userRepository.findByEmail(a.email).orElseThrow();
        a.other = browser();
        MvcResult first = login(a.other, a.email, PASSWORD);
        assertEquals("mfa_required", json(first).path("status").asText(), content(first));
        MvcResult verified = a.other.post(BASE + "/mfa/verify", Map.of("mfaToken", json(first).get("mfaToken").asText(), "method", "totp",
                "code", totpCode(a.totp), "rememberDevice", true));
        assertEquals(200, status(verified), content(verified));
        nextTotpStep();
        Pkce2 pkce = Pkce2.create();
        Map<String, String> p = authorizeParams("app-a", APP_A_REDIRECT, "openid offline_access", pkce);
        String code = codeFrom(get(a.other, url("/oauth2/authorize", p)), APP_A_REDIRECT, p.get("state"));
        a.offlineRefresh = exchange(a.other, "app-a", null, code, APP_A_REDIRECT, pkce).get("refresh_token").asText();
        assertEquals(202, status(a.other.post(BASE + "/magic-link/request", Map.of("email", a.email, "redirect", "/"))));
        Matcher m = Pattern.compile("token=([^&]+)").matcher(transport.last(a.email).orElseThrow().vars().get("link"));
        assertTrue(m.find());
        a.magicToken = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        a.codePkce = Pkce2.create();
        Map<String, String> pending = authorizeParams("app-a", APP_A_REDIRECT, "openid", a.codePkce);
        a.code = codeFrom(get(a.other, url("/oauth2/authorize", pending)), APP_A_REDIRECT, pending.get("state"));
        MvcResult device = form(browser(), "/oauth2/device_authorization", Map.of("client_id", "tv", "scope", "openid profile"), null);
        assertEquals(200, status(device), content(device));
        a.deviceCode = json(device).get("device_code").asText();
        MvcResult decision = a.other.post("/oauth2/device/decision", Map.of("userCode", json(device).get("user_code").asText(), "approve", true));
        assertEquals("approved", json(decision).path("status").asText(), content(decision));
        a.mfaPending = browser();
        MvcResult mfa = login(a.mfaPending, a.email, PASSWORD);
        a.mfaToken = json(mfa).get("mfaToken").asText();
        a.pushPending = browser();
        MvcResult pushLogin = login(a.pushPending, a.email, PASSWORD);
        a.pushToken = json(pushLogin).get("mfaToken").asText();
        MvcResult challenge = a.pushPending.post(BASE + "/mfa/challenge", Map.of("mfaToken", a.pushToken, "method", "push"));
        assertEquals(200, status(challenge), content(challenge));
        a.pushId = json(challenge).get("pushId").asText();
        MvcResult respond = a.other.post(BASE + "/push/" + a.pushId + "/respond", Map.of("number", json(challenge).get("number").asText(),
                "approve", true));
        assertEquals(200, status(respond), content(respond));
        int sent = transport.to(a.phone).size();
        a.otpPending = browser();
        assertEquals(202, status(a.otpPending.post(BASE + "/otp/request", Map.of("channel", "sms", "destination", a.phone))));
        a.smsCode = transport.to(a.phone).get(sent).vars().get("code");
        a.resetToken = authService.forgotPassword(new ForgotPasswordRequest(a.email)).uuid();
        clock.advance(Duration.ofSeconds(2));
        return a;
    }

    private void run(Event event, Account a) {
        String userId = String.valueOf(a.user.getId());
        switch (event) {
            case NONE -> {
            }
            case EMAIL_VERIFIED -> {
                Browser owner = browser();
                assertEquals(202, status(owner.post(BASE + "/otp/request", Map.of("channel", "email", "destination", a.email))));
                String otp = transport.last(a.email).orElseThrow().vars().get("code");
                MvcResult r = owner.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", a.email, "code", otp));
                assertEquals(200, status(r), content(r));
                assertTrue(userRepository.findById(a.user.getId()).orElseThrow().isEmailVerified());
            }
            case EMAIL_CHANGED -> {
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "replace");
                op.put("path", "emails");
                op.put("value", List.of(Map.of("value", uniqueEmail("matrix-moved"), "primary", true)));
                MvcResult r = scim().patch("/scim/v2/Users/" + a.scimId,
                        Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"), "Operations", List.of(op)));
                assertEquals(200, status(r), content(r));
            }
            case PASSWORD_RESET -> {
                String uuid = UUID.randomUUID().toString();
                passwordResetRepository.save(PasswordResetEntity.builder().email(a.email).uuid(uuid)
                        .expiresAt(clock.instant().plus(Duration.ofMinutes(30))).build());
                MvcResult r = browser().post(BASE + "/change-password", Map.of("email", a.email, "uuid", uuid, "password", "N3w-Horse-Battery-Staple!"));
                assertEquals(200, status(r), content(r));
            }
            case FACTOR_ADDED -> {
                SoftAuthenticator key = new SoftAuthenticator();
                JsonNode options = json(a.current.post(BASE + "/webauthn/register/options", Map.of("mode", "biometric", "label", "Phone")));
                MvcResult r = a.current.post(BASE + "/webauthn/register/verify",
                        Map.of("credential", parse(key.register(options, SoftAuthenticator.UP | SoftAuthenticator.UV))));
                assertEquals(200, status(r), content(r));
            }
            case FACTOR_REMOVED -> assertEquals(204, status(a.current.delete(BASE + "/mfa/factors/totp")));
            case LINK_ADDED -> {
                MvcResult start = a.current.post(BASE + "/account/links/google/start", null);
                assertEquals(200, status(start), content(start));
                String sid = Jwts.payload(a.current.cookie("access_token")).get("sid").asText();
                links.complete(json(start).get("linkIntent").asText(), userId, sid,
                        new AccountLinkService.Identity("google", "g-matrix-" + userId, a.email, true));
            }
            case LINK_REMOVED -> {
                links.bind(userId, new AccountLinkService.Identity("google", "g-matrix-" + userId, a.email, true));
                assertEquals(204, status(a.current.delete(BASE + "/account/links/google")));
            }
            case DIRECTORY_ADOPTION -> {
                String login = "matrix-" + Bytes.randomToken(6).replaceAll("[^A-Za-z0-9]", "x");
                DIRECTORY.add(login, "dir-pass", "uid=" + login + ",ou=people,dc=corp", Map.of("mail", List.of(a.email)));
                MvcResult r = login(browser().clientId("corp-app"), login, "dir-pass");
                assertEquals(200, status(r), content(r));
                assertEquals(1, mongo.getCollection("sso_directory_accounts").countDocuments(new Document("userId", userId)));
            }
            case IDP_LINKS_REMOVED -> {
                String provider = "saml:matrix-" + userId;
                links.bind(userId, new AccountLinkService.Identity(provider, "nameid-" + userId, a.email, true));
                assertEquals(1, links.removeProvider(provider));
            }
            case SCIM_PASSWORD_SET -> {
                MvcResult r = scim().patch("/scim/v2/Users/" + a.scimId, patch("password", "Scim-Set-Battery-Staple!"));
                assertEquals(200, status(r), content(r));
            }
            case SCIM_DEACTIVATED -> {
                MvcResult r = scim().patch("/scim/v2/Users/" + a.scimId, patch("active", false));
                assertEquals(200, status(r), content(r));
            }
            case RECOVERY_REGENERATED -> {
                MvcResult r = a.current.post(BASE + "/mfa/recovery/regenerate", null);
                assertEquals(200, status(r), content(r));
            }
            case ADMIN_FACTOR_RESET -> {
                MvcResult r = usersAdmin().post(ADMIN + "/users/" + userId + "/factors/reset", Map.of("reason", "matrix"));
                assertEquals(204, status(r), content(r));
            }
            case ROLE_REMOVED -> {
                String t = Bytes.randomToken(6).replaceAll("[^A-Za-z0-9]", "X").toUpperCase();
                UserEntity manager = createUser(uniqueEmail("matrix-manager"), passwordService.hash(PASSWORD),
                        role("MATRIX_MANAGER_" + t, "users:update", "profile:read"));
                role("MATRIX_SUPPORT_" + t, "profile:read");
                Browser m = loggedIn(manager.getEmail());
                assertEquals(200, status(m.post("/api/admin/users/" + userId + "/roles/MATRIX_SUPPORT_" + t, null)));
                MvcResult r = m.delete("/api/admin/users/" + userId + "/roles/MATRIX_SUPPORT_" + t);
                assertEquals(200, status(r), content(r));
            }
            case SESSIONS_REVOKED_OTHERS -> assertEquals(204, status(a.current.post(BASE + "/sessions/revoke-others", null)));
            case ADMIN_SESSIONS_REVOKED -> {
                MvcResult r = usersAdmin().delete(ADMIN + "/users/" + userId + "/sessions");
                assertEquals(204, status(r), content(r));
            }
            case DISABLED -> {
                String t = Bytes.randomToken(6).replaceAll("[^A-Za-z0-9]", "X").toUpperCase();
                UserEntity manager = createUser(uniqueEmail("matrix-disabler"), passwordService.hash(PASSWORD),
                        role("MATRIX_DISABLER_" + t, "users:disable", "profile:read"));
                MvcResult r = loggedIn(manager.getEmail()).patch("/api/admin/users/" + userId + "/disable", null);
                assertEquals(200, status(r), content(r));
            }
            case ERASED -> {
                MvcResult r = usersAdmin().delete(ADMIN + "/users/" + userId);
                assertEquals(200, status(r), content(r));
            }
            case ADMIN_ROLE_GRANTED -> {
                String t = Bytes.randomToken(6).replaceAll("[^A-Za-z0-9]", "X").toUpperCase();
                UserEntity manager = createUser(uniqueEmail("matrix-manager"), passwordService.hash(PASSWORD),
                        role("MATRIX_MANAGER_" + t, "users:update", "profile:read"));
                role("MATRIX_ADMIN_" + t, "users:update", "profile:read");
                MvcResult r = loggedIn(manager.getEmail()).post("/api/admin/users/" + userId + "/roles/MATRIX_ADMIN_" + t, null);
                assertEquals(200, status(r), content(r));
            }
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("SSO-T-auth-775 F-CONF-29 a SCIM email change ends the reset token and the email sign-in code of the previous address: neither survives in the stores, the stale reset link answers 400 (not 500), and an account that takes the previous address later is neither reset nor signed in by them")
    void emailChangeEndsThePreviousAddressCredentials() {
        role("USER", "profile:read");
        String previous = uniqueEmail("moved-from");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", previous);
        body.put("emails", List.of(Map.of("value", previous, "primary", true)));
        body.put("active", true);
        MvcResult created = scim().post("/scim/v2/Users", body);
        assertEquals(201, status(created), content(created));
        String scimId = json(created).get("id").asText();
        UserEntity moved = userRepository.findByEmail(previous).orElseThrow();
        moved.setPassword(passwordService.hash(PASSWORD));
        moved.setEmailVerified(true);
        userRepository.save(moved);
        String resetToken = authService.forgotPassword(new ForgotPasswordRequest(previous)).uuid();
        Browser holder = browser();
        assertEquals(202, status(holder.post(BASE + "/otp/request", Map.of("channel", "email", "destination", previous))));
        String code = transport.last(previous).orElseThrow().vars().get("code");
        String otpPurpose = com.naqqa.auth.sso.flow.SecondFactors.loginPurpose(com.naqqa.sso.otp.Channel.EMAIL, previous);
        assertTrue(mongo.getCollection("sso_otp").countDocuments(new Document("purpose", otpPurpose)) > 0);

        MvcResult changed = scim().patch("/scim/v2/Users/" + scimId, patch("emails", List.of(Map.of("value", uniqueEmail("moved-to"), "primary", true))));
        assertEquals(200, status(changed), content(changed));

        assertTrue(passwordResetRepository.findById(previous).isEmpty(), "the reset record of the previous address survived");
        assertEquals(0, mongo.getCollection("sso_otp").countDocuments(new Document("purpose", otpPurpose)),
                "the email sign-in code of the previous address survived");
        MvcResult stale = browser().post(BASE + "/change-password", Map.of("email", previous, "uuid", resetToken, "password", "Quiet-Harbor-Lantern-42!"));
        assertEquals(400, status(stale), content(stale));

        UserEntity successor = user(previous);
        String hashBefore = userRepository.findById(successor.getId()).orElseThrow().getPassword();
        MvcResult reset = browser().post(BASE + "/change-password", Map.of("email", previous, "uuid", resetToken, "password", "Quiet-Harbor-Lantern-43!"));
        assertTrue(status(reset) != 200, content(reset));
        assertEquals(hashBefore, userRepository.findById(successor.getId()).orElseThrow().getPassword());
        MvcResult otp = holder.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", previous, "code", code));
        assertTrue(status(otp) != 200, content(otp));
        assertEquals(401, status(holder.get(BASE + "/test/me")));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("SSO-T-auth-777 a SCIM request that changes the email and deactivates the account in one operation ends the reset token and the email sign-in code of the previous address too (the deactivation hook revokes before the sync moves the address)")
    void emailChangeWithDeactivationEndsThePreviousAddressCredentials() {
        role("USER", "profile:read");
        String previous = uniqueEmail("moved-off");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", previous);
        body.put("emails", List.of(Map.of("value", previous, "primary", true)));
        body.put("active", true);
        MvcResult created = scim().post("/scim/v2/Users", body);
        assertEquals(201, status(created), content(created));
        String scimId = json(created).get("id").asText();
        UserEntity moved = userRepository.findByEmail(previous).orElseThrow();
        moved.setPassword(passwordService.hash(PASSWORD));
        moved.setEmailVerified(true);
        userRepository.save(moved);
        String resetToken = authService.forgotPassword(new ForgotPasswordRequest(previous)).uuid();
        Browser holder = browser();
        assertEquals(202, status(holder.post(BASE + "/otp/request", Map.of("channel", "email", "destination", previous))));
        String code = transport.last(previous).orElseThrow().vars().get("code");
        String otpPurpose = com.naqqa.auth.sso.flow.SecondFactors.loginPurpose(com.naqqa.sso.otp.Channel.EMAIL, previous);
        assertTrue(mongo.getCollection("sso_otp").countDocuments(new Document("purpose", otpPurpose)) > 0);

        Map<String, Object> email = new LinkedHashMap<>();
        email.put("op", "replace");
        email.put("path", "emails");
        email.put("value", List.of(Map.of("value", uniqueEmail("moved-on"), "primary", true)));
        Map<String, Object> active = new LinkedHashMap<>();
        active.put("op", "replace");
        active.put("path", "active");
        active.put("value", false);
        MvcResult changed = scim().patch("/scim/v2/Users/" + scimId,
                Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"), "Operations", List.of(email, active)));
        assertEquals(200, status(changed), content(changed));
        assertTrue(!userRepository.findById(moved.getId()).orElseThrow().isEnabled());

        assertTrue(passwordResetRepository.findById(previous).isEmpty(), "the reset record of the previous address survived");
        assertEquals(0, mongo.getCollection("sso_otp").countDocuments(new Document("purpose", otpPurpose)),
                "the email sign-in code of the previous address survived");
        UserEntity successor = user(previous);
        String hashBefore = userRepository.findById(successor.getId()).orElseThrow().getPassword();
        MvcResult reset = browser().post(BASE + "/change-password", Map.of("email", previous, "uuid", resetToken, "password", "Quiet-Harbor-Lantern-44!"));
        assertTrue(status(reset) != 200, content(reset));
        assertEquals(hashBefore, userRepository.findById(successor.getId()).orElseThrow().getPassword());
        MvcResult otp = holder.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", previous, "code", code));
        assertTrue(status(otp) != 200, content(otp));
    }

    private Browser usersAdmin() {
        String t = Bytes.randomToken(6).replaceAll("[^A-Za-z0-9]", "X").toUpperCase();
        UserEntity admin = createUser(uniqueEmail("matrix-operator"), passwordService.hash(PASSWORD),
                role("MATRIX_USERS_" + t, "SSO_ADMIN_USERS", "profile:read"));
        defaultTenantAdmin(admin);
        return loggedIn(admin.getEmail());
    }

    private static Map<String, Object> patch(String path, Object value) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "replace");
        op.put("path", path);
        op.put("value", value);
        return Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"), "Operations", List.of(op));
    }

    private Browser scim() {
        return browser().bearer(apiTokens.issue("default", ApiTokenKind.SECRET, ApiTokenEnv.TEST, "scim", List.of("scim"), List.of()).token());
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
