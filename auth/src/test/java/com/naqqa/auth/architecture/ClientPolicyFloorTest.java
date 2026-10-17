package com.naqqa.auth.architecture;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoClientSettingsStore;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.sso.flow.SsoLoginFlow;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.OidcTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "naqqa.sso.mfa.required-for-admin=true",
        "naqqa.sso.email-otp.enabled=true",
        "naqqa.sso.sms-otp.enabled=true",
        "naqqa.sso.push.enabled=true"
})
class ClientPolicyFloorTest extends OidcTestBase {

    static final String PROBE = "floor-probe";

    enum Kind { METHOD, REQUIREMENT, NEUTRAL }

    static final Map<String, Kind> CLIENT_SETTINGS = Map.ofEntries(
            Map.entry("password", Kind.METHOD),
            Map.entry("magicLink", Kind.METHOD),
            Map.entry("emailOtp", Kind.METHOD),
            Map.entry("smsOtp", Kind.METHOD),
            Map.entry("totp", Kind.METHOD),
            Map.entry("recovery", Kind.METHOD),
            Map.entry("passkey", Kind.METHOD),
            Map.entry("biometric", Kind.METHOD),
            Map.entry("securityKey", Kind.METHOD),
            Map.entry("push", Kind.METHOD),
            Map.entry("saml", Kind.METHOD),
            Map.entry("samlSp", Kind.METHOD),
            Map.entry("mfaRequiredForAll", Kind.REQUIREMENT),
            Map.entry("mfaRequiredRoles", Kind.REQUIREMENT),
            Map.entry("enabled", Kind.NEUTRAL),
            Map.entry("socialProviders", Kind.NEUTRAL),
            Map.entry("allowedOrigins", Kind.NEUTRAL),
            Map.entry("redirectUris", Kind.NEUTRAL),
            Map.entry("ldapDirectory", Kind.NEUTRAL));

    static final Map<String, Kind> STORED_SETTINGS = Map.ofEntries(
            Map.entry("clientId", Kind.NEUTRAL),
            Map.entry("tenantId", Kind.NEUTRAL),
            Map.entry("enabled", Kind.NEUTRAL),
            Map.entry("methods", Kind.METHOD),
            Map.entry("socialProviders", Kind.NEUTRAL),
            Map.entry("allowedOrigins", Kind.NEUTRAL),
            Map.entry("brandingName", Kind.NEUTRAL),
            Map.entry("logoUrl", Kind.NEUTRAL),
            Map.entry("updatedAt", Kind.NEUTRAL));

    static final Set<String> STRONG = Set.of("totp", "recovery", "passkey", "biometric", "securityKey");
    static final Set<String> WEAK = Set.of("magicLink", "emailOtp", "smsOtp", "push");

    record Variant(String name, Consumer<SsoProperties.Client> configured, Map<String, Boolean> stored) {
    }

    record Probe(String name, UserEntity user) {
    }

    @Autowired
    SsoProperties properties;
    @Autowired
    SsoClientPolicy clientPolicy;
    @Autowired
    SsoLoginFlow loginFlow;
    @Autowired
    SsoClientSettingsStore settingsStore;

    boolean risk;

    @BeforeEach
    void quiet() {
        risk = properties.getRisk().isEnabled();
        properties.getRisk().setEnabled(false);
    }

    @AfterEach
    void restore() {
        properties.getRisk().setEnabled(risk);
        properties.getClients().remove(PROBE);
        settingsStore.delete(PROBE);
    }

    @Test
    @DisplayName("SSO-T-auth-771 F-CONF-20 class: every per-client setting a caller can select (properties and stored settings) is classified, and none weakens the global or account rule: for every account kind (plain, MFA role, administrator without and with a factor, TOTP) the sign-in outcome, the enrollment restriction and the OIDC acr through the client are at least as strict as without it; no client lifetime is bound (session, refresh and step-up lifetimes are global only, O7)")
    void clientSettingsOnlyTighten() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Field f : SsoProperties.Client.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !CLIENT_SETTINGS.containsKey(f.getName())) {
                problems.add("naqqa.sso.clients.<id>." + f.getName() + " has no strictest-wins classification");
            }
        }
        for (RecordComponent rc : SsoClientSettingsStore.Settings.class.getRecordComponents()) {
            if (!STORED_SETTINGS.containsKey(rc.getName())) {
                problems.add("stored client setting " + rc.getName() + " has no strictest-wins classification");
            }
        }
        for (String m : SsoClientSettingsStore.METHODS) {
            if (CLIENT_SETTINGS.get(m) != Kind.METHOD) {
                problems.add("stored method switch " + m + " is not a classified method");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));

        List<Probe> probes = probes();
        Map<String, Integer> global = new LinkedHashMap<>();
        for (Probe p : probes) {
            global.put(p.name(), outcome(p, null));
        }
        for (Variant v : variants()) {
            properties.getClients().remove(PROBE);
            settingsStore.delete(PROBE);
            SsoProperties.Client c = new SsoProperties.Client();
            v.configured().accept(c);
            properties.getClients().put(PROBE, c);
            if (v.stored() != null) {
                settingsStore.save(new SsoClientSettingsStore.Settings(PROBE, "default", true, v.stored(), null, List.of(), null, null,
                        clock.instant()));
            }
            SsoClientPolicy.Policy policy = clientPolicy.forClient(PROBE);
            SsoClientPolicy.Policy base = clientPolicy.forClient(null);
            for (Probe p : probes) {
                int through = outcome(p, PROBE);
                if (through < global.get(p.name())) {
                    problems.add(v.name() + " weakens the sign-in of " + p.name() + ": " + rank(through) + " instead of " + rank(global.get(p.name())));
                }
                UserEntity u = userRepository.findById(p.user().getId()).orElseThrow();
                if (loginFlow.enrollmentRequired(u, base) && !loginFlow.enrollmentRequired(u, policy)) {
                    problems.add(v.name() + " lifts the enrollment restriction of " + p.name());
                }
                int acrThrough = level(loginFlow.requiredAcr(u, null, List.of("pwd"), null, policy));
                int acrGlobal = level(loginFlow.requiredAcr(u, null, List.of("pwd"), null, base));
                if (acrThrough < acrGlobal) {
                    problems.add(v.name() + " lowers the OIDC acr required of " + p.name() + ": " + acrThrough + " instead of " + acrGlobal);
                }
            }
        }
        properties.getClients().remove(PROBE);
        settingsStore.delete(PROBE);
        lifetimes(problems);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private void lifetimes(List<String> problems) {
        for (Field f : SsoProperties.Client.class.getDeclaredFields()) {
            if (Duration.class.equals(f.getType())) {
                problems.add("naqqa.sso.clients.<id>." + f.getName() + " is a client lifetime: none is read, so none may be bound (O7)");
            }
        }
        properties.getClients().put(PROBE, new SsoProperties.Client());
        if (!clientPolicy.forClient(PROBE).stepUpMaxAge().equals(properties.getMfa().getStepUpMaxAge())) {
            problems.add("the client policy step-up-max-age differs from the global " + properties.getMfa().getStepUpMaxAge());
        }
        properties.getClients().remove(PROBE);
    }

    private List<Probe> probes() {
        List<Probe> out = new ArrayList<>();
        out.add(new Probe("plain", user(uniqueEmail("floor-plain"))));
        out.add(new Probe("mfa-role", createUser(uniqueEmail("floor-support"), passwordService.hash(PASSWORD), role("SUPPORT", "profile:read"))));
        out.add(new Probe("admin", createUser(uniqueEmail("floor-admin"), passwordService.hash(PASSWORD), role("ADMIN", "profile:read"))));
        UserEntity adminTotp = createUser(uniqueEmail("floor-admin-totp"), passwordService.hash(PASSWORD), role("ADMIN", "profile:read"));
        enrollTotp(secondChannel(adminTotp.getEmail()));
        out.add(new Probe("admin-totp", adminTotp));
        UserEntity totp = user(uniqueEmail("floor-totp"));
        enrollTotp(loggedIn(totp.getEmail()));
        out.add(new Probe("totp", totp));
        return out;
    }

    private Browser secondChannel(String email) {
        Browser b = browser();
        MvcResult first = login(b, email, PASSWORD);
        JsonNode body = json(first);
        if ("mfa_required".equals(body.path("status").asText())) {
            String token = body.get("mfaToken").asText();
            int sent = transport.to(email).size();
            assertTrue(status(b.post(BASE + "/mfa/challenge", Map.of("mfaToken", token, "method", "email_otp"))) == 200, content(first));
            String code = transport.to(email).get(sent).vars().get("code");
            MvcResult v = b.post(BASE + "/mfa/verify", Map.of("mfaToken", token, "method", "email_otp", "code", code));
            assertTrue(status(v) == 200, content(v));
        }
        return b;
    }

    private List<Variant> variants() {
        List<Variant> out = new ArrayList<>();
        for (Map.Entry<String, Kind> e : CLIENT_SETTINGS.entrySet()) {
            if (e.getValue() != Kind.METHOD) {
                continue;
            }
            for (boolean value : List.of(true, false)) {
                out.add(new Variant(e.getKey() + "=" + value, c -> set(c, e.getKey(), value), null));
            }
        }
        for (String m : SsoClientSettingsStore.METHODS) {
            for (boolean value : List.of(true, false)) {
                out.add(new Variant("stored " + m + "=" + value, c -> {
                }, Map.of(m, value)));
            }
        }
        out.add(new Variant("all methods off", c -> CLIENT_SETTINGS.forEach((k, v) -> {
            if (v == Kind.METHOD && !"password".equals(k)) {
                set(c, k, false);
            }
        }), null));
        out.add(new Variant("strong factors off", c -> STRONG.forEach(k -> set(c, k, false)), null));
        out.add(new Variant("weak methods off", c -> WEAK.forEach(k -> set(c, k, false)), null));
        Map<String, Boolean> storedOff = new LinkedHashMap<>();
        SsoClientSettingsStore.METHODS.stream().filter(m -> !"password".equals(m)).forEach(m -> storedOff.put(m, false));
        out.add(new Variant("stored all methods off", c -> {
        }, storedOff));
        out.add(new Variant("mfaRequiredForAll=false", c -> c.setMfaRequiredForAll(false), null));
        out.add(new Variant("mfaRequiredForAll=true", c -> c.setMfaRequiredForAll(true), null));
        out.add(new Variant("mfaRequiredRoles=[]", c -> c.setMfaRequiredRoles(List.of()), null));
        out.add(new Variant("mfaRequiredRoles=[OTHER]", c -> c.setMfaRequiredRoles(List.of("OTHER")), null));
        return out;
    }

    private static void set(SsoProperties.Client c, String field, Boolean value) {
        try {
            Field f = SsoProperties.Client.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(c, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private int outcome(Probe p, String clientId) {
        Browser b = browser();
        if (clientId != null) {
            b.clientId(clientId);
        }
        MvcResult r = login(b, p.user().getEmail(), PASSWORD);
        if (status(r) != 200) {
            return 2;
        }
        JsonNode body = json(r);
        if (!"ok".equals(body.path("status").asText())) {
            return 2;
        }
        return body.path("mfaEnrollmentRequired").asBoolean() ? 1 : 0;
    }

    private static String rank(int r) {
        return switch (r) {
            case 0 -> "a full session";
            case 1 -> "a restricted session";
            default -> "a second factor or a refusal";
        };
    }

    private static int level(String acr) {
        return acr == null ? 0 : AssuranceEngine.levelOfAcr(acr);
    }
}
