package com.naqqa.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.auth.dto.auth.ForgotPasswordRequest;
import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.service.auth.AuthService;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.flow.AssuranceEngine;
import com.naqqa.auth.sso.flow.AssuranceEngine.Factor;
import com.naqqa.auth.sso.flow.AssuranceEngine.Operation;
import com.naqqa.auth.sso.flow.SsoClientPolicy;
import com.naqqa.auth.support.Browser;
import com.naqqa.auth.support.CapturingTransport;
import com.naqqa.auth.support.OidcTestBase;
import com.naqqa.auth.support.SoftAuthenticator;
import com.naqqa.sso.util.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRound10AuthTest extends OidcTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final List<String> STEP_UP_METHODS = List.of("totp", "webauthn", "recovery", "email_otp", "sms_otp", "push", "password");

    enum Cap { PASSWORD, MAILBOX, SIM, TOTP_DEVICE, PASSKEY_DEVICE, SECURITY_KEY, RECOVERY_CODE }

    record Sess(Set<String> amr, int strength) {
    }

    record First(String route, List<String> amr, Set<String> excluded) {
    }

    record World(Set<Factor> account, Set<Factor> controlled, Set<Sess> sessions, boolean remembered, Set<String> secondFactors) {
    }

    @Autowired
    SsoProperties properties;

    @Autowired
    AuthService authService;

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> methods(MvcResult r) {
        List<String> out = new ArrayList<>();
        json(r).path("methods").forEach(n -> out.add(n.asText()));
        return out;
    }

    private SoftAuthenticator registerKey(Browser owner, String mode, int flags) {
        SoftAuthenticator key = new SoftAuthenticator();
        JsonNode options = json(owner.post(BASE + "/webauthn/register/options", Map.of("mode", mode, "label", "Key")));
        MvcResult r = owner.post(BASE + "/webauthn/register/verify", Map.of("credential", parse(key.register(options, flags))));
        assertEquals(200, status(r), content(r));
        return key;
    }

    private MvcResult keyLogin(Browser b, String email, SoftAuthenticator key, int flags) {
        JsonNode options = json(b.post(BASE + "/webauthn/login/options", Map.of("email", email)));
        return b.post(BASE + "/webauthn/login/verify", Map.of("credential", parse(key.assertion(options, flags))));
    }

    @Test
    @DisplayName("SSO-T-auth-642 F-A10-AUTH-1 a security key without user verification is a possession factor only: alone it does not sign in to an account whose only strong factor is WebAuthn, the password completes it, and without any second factor sign-in is refused")
    void securityKeyWithoutUserVerificationNeedsASecondFactor() {
        boolean push = properties.getPush().isEnabled();
        properties.getPush().setEnabled(false);
        try {
            String email = uniqueEmail("atk10-key");
            UserEntity user = user(email);
            SoftAuthenticator key = registerKey(loggedIn(email), "security_key", SoftAuthenticator.UP);
            clock.advance(Duration.ofSeconds(2));

            Browser thief = browser();
            MvcResult first = keyLogin(thief, email, key, SoftAuthenticator.UP);
            assertEquals(200, status(first), content(first));
            assertEquals("mfa_required", json(first).path("status").asText(), content(first));
            assertEquals(List.of("password"), methods(first), content(first));
            assertEquals(401, status(thief.get(BASE + "/test/me")));

            MvcResult done = thief.post(BASE + "/mfa/verify", Map.of("mfaToken", json(first).get("mfaToken").asText(), "method", "password",
                    "code", PASSWORD));
            assertEquals(200, status(done), content(done));
            assertEquals(200, status(thief.get(BASE + "/test/fresh")));

            Browser verified = browser();
            MvcResult uv = keyLogin(verified, email, key, SoftAuthenticator.UP | SoftAuthenticator.UV);
            assertEquals("ok", json(uv).path("status").asText(), content(uv));

            user.setPassword(null);
            userRepository.save(user);
            Browser none = browser();
            MvcResult refused = keyLogin(none, email, key, SoftAuthenticator.UP);
            assertEquals(403, status(refused), content(refused));
            assertEquals("USER_VERIFICATION_REQUIRED", json(refused).path("code").asText(), content(refused));
            assertEquals(401, status(none.get(BASE + "/test/me")));
        } finally {
            properties.getPush().setEnabled(push);
        }
    }

    static <E extends Enum<E>> List<Set<E>> subsets(Class<E> type) {
        E[] all = type.getEnumConstants();
        List<Set<E>> out = new ArrayList<>();
        for (int mask = 0; mask < (1 << all.length); mask++) {
            Set<E> s = EnumSet.noneOf(type);
            for (int i = 0; i < all.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    s.add(all[i]);
                }
            }
            out.add(s);
        }
        return out;
    }

    static SsoClientPolicy.Policy policy(boolean push, boolean mfaForAll) {
        return new SsoClientPolicy.Policy(null, true, true, true, true, true, true, true, true, true, push, false, false, List.of("google"), List.of(),
                mfaForAll, Set.of(), Duration.ofMinutes(10), 8, 128, null);
    }

    static Set<Factor> effectiveAccount(Set<Factor> account, Set<Cap> caps) {
        Set<Factor> out = EnumSet.copyOf(account.isEmpty() ? EnumSet.noneOf(Factor.class) : account);
        if (caps.contains(Cap.MAILBOX) && account.contains(Factor.EMAIL)) {
            out.add(Factor.PASSWORD);
        }
        return out;
    }

    static Set<Factor> controlled(Set<Factor> account, Set<Cap> caps) {
        Set<Factor> c = EnumSet.noneOf(Factor.class);
        boolean mailbox = caps.contains(Cap.MAILBOX) && account.contains(Factor.EMAIL);
        if (mailbox) {
            c.add(Factor.EMAIL);
        }
        if (mailbox || caps.contains(Cap.PASSWORD) && account.contains(Factor.PASSWORD)) {
            c.add(Factor.PASSWORD);
        }
        if (caps.contains(Cap.SIM) && account.contains(Factor.PHONE)) {
            c.add(Factor.PHONE);
        }
        if (caps.contains(Cap.TOTP_DEVICE) && account.contains(Factor.TOTP)) {
            c.add(Factor.TOTP);
        }
        if (caps.contains(Cap.PASSKEY_DEVICE) && account.contains(Factor.PASSKEY)) {
            c.add(Factor.PASSKEY);
        }
        if (caps.contains(Cap.SECURITY_KEY) && account.contains(Factor.SECURITY_KEY)) {
            c.add(Factor.SECURITY_KEY);
        }
        if (caps.contains(Cap.RECOVERY_CODE) && account.contains(Factor.RECOVERY_CODES)) {
            c.add(Factor.RECOVERY_CODES);
        }
        return c;
    }

    static int capability(Set<Factor> controlled) {
        int max = 0;
        for (Factor f : controlled) {
            max = Math.max(max, switch (f) {
                case PASSKEY, SECURITY_KEY -> 4;
                case TOTP, RECOVERY_CODES -> 3;
                case EMAIL, PHONE -> 2;
                case PASSWORD -> 1;
            });
        }
        return max;
    }

    static boolean policyPair(Set<Factor> c) {
        if (c.contains(Factor.PASSKEY)) {
            return true;
        }
        for (Factor first : List.of(Factor.PASSWORD, Factor.EMAIL, Factor.PHONE, Factor.SECURITY_KEY)) {
            if (!c.contains(first)) {
                continue;
            }
            for (Factor second : List.of(Factor.TOTP, Factor.RECOVERY_CODES, Factor.SECURITY_KEY, Factor.PASSWORD)) {
                if (second != first && c.contains(second) && (second != Factor.PASSWORD || first == Factor.SECURITY_KEY)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<First> firstFactors(Set<Factor> account, Set<Factor> c, boolean social) {
        List<First> out = new ArrayList<>();
        if (c.contains(Factor.PASSWORD)) {
            out.add(new First("password", List.of("pwd"), Set.of()));
        }
        if (c.contains(Factor.EMAIL)) {
            out.add(new First("email", List.of("otp"), Set.of("email_otp")));
            if (social && AssuranceEngine.mayAutoLink(account)) {
                out.add(new First("social", List.of("fed"), Set.of()));
            }
        }
        if (c.contains(Factor.PHONE)) {
            out.add(new First("sms", List.of("sms"), Set.of("sms_otp")));
        }
        if (c.contains(Factor.PASSKEY)) {
            out.add(new First("passkey", List.of("swk", "user"), Set.of("webauthn")));
        }
        if (c.contains(Factor.SECURITY_KEY)) {
            out.add(new First("security_key", List.of("hwk"), Set.of("webauthn")));
        }
        return out;
    }

    static List<List<String>> proofs(String method, Set<Factor> account, Set<Factor> c, Set<Sess> sessions) {
        List<List<String>> out = new ArrayList<>();
        switch (method) {
            case "totp" -> {
                if (c.contains(Factor.TOTP)) {
                    out.add(List.of("otp"));
                }
            }
            case "recovery" -> {
                if (c.contains(Factor.RECOVERY_CODES)) {
                    out.add(List.of("otp"));
                }
            }
            case "email_otp" -> {
                if (c.contains(Factor.EMAIL)) {
                    out.add(List.of("otp"));
                }
            }
            case "sms_otp" -> {
                if (c.contains(Factor.PHONE)) {
                    out.add(List.of("sms"));
                }
            }
            case "password" -> {
                if (c.contains(Factor.PASSWORD)) {
                    out.add(List.of("pwd"));
                }
            }
            case "webauthn" -> {
                if (c.contains(Factor.PASSKEY)) {
                    out.add(List.of("swk", "user"));
                }
                if (c.contains(Factor.SECURITY_KEY)) {
                    out.add(List.of("hwk"));
                }
            }
            case "push" -> {
                if (sessions.stream().anyMatch(s -> AssuranceEngine.permits(Operation.APPROVE_PUSH, account, s.strength(), false))) {
                    out.add(List.of("mca"));
                }
            }
            default -> {
            }
        }
        return out;
    }

    static Set<String> amr(Collection<String> a, Collection<String> b, boolean mfa) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        if (mfa) {
            out.add("mfa");
        }
        return out;
    }

    static World explore(Set<Factor> account, Set<Cap> caps, SsoClientPolicy.Policy policy, boolean policyRequired, boolean administrative,
                         boolean social) {
        Set<Factor> effective = effectiveAccount(account, caps);
        Set<Factor> c = controlled(account, caps);
        Set<Sess> sessions = new LinkedHashSet<>();
        Set<String> used = new TreeSet<>();
        boolean remembered = false;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (First f : firstFactors(effective, c, social)) {
                AssuranceEngine.SignIn r = AssuranceEngine.signIn(effective, f.amr(), policy, policy, f.excluded(), policyRequired, administrative, false,
                        remembered);
                if (r.outcome() == AssuranceEngine.Outcome.COMPLETE) {
                    changed |= sessions.add(new Sess(new TreeSet<>(f.amr()), r.strength()));
                }
                if (r.outcome() != AssuranceEngine.Outcome.SECOND_FACTOR) {
                    continue;
                }
                for (String m : r.methods()) {
                    if (!AssuranceEngine.acceptsSecondFactor(effective, f.amr(), m, administrative)) {
                        continue;
                    }
                    for (List<String> proof : proofs(m, effective, c, sessions)) {
                        used.add(m);
                        int strength = AssuranceEngine.combinedStrength(f.amr(), m);
                        changed |= sessions.add(new Sess(amr(f.amr(), proof, true), strength));
                        if (!remembered && AssuranceEngine.permits(Operation.REMEMBER_DEVICE, effective, strength, true)) {
                            remembered = true;
                            changed = true;
                        }
                    }
                }
            }
            for (Sess s : List.copyOf(sessions)) {
                for (String m : STEP_UP_METHODS) {
                    if (!AssuranceEngine.enabled(policy, m) || !AssuranceEngine.stepUpAccepted(effective, m)) {
                        continue;
                    }
                    for (List<String> proof : proofs(m, effective, c, sessions)) {
                        changed |= sessions.add(new Sess(amr(s.amr(), proof, false), AssuranceEngine.stepUpStrength(s.amr(), m, proof)));
                    }
                }
            }
        }
        return new World(effective, c, sessions, remembered, used);
    }

    static int expectedClass(Set<Factor> account) {
        if (account.contains(Factor.PASSKEY) || account.contains(Factor.SECURITY_KEY)) {
            return 4;
        }
        return account.contains(Factor.TOTP) ? 3 : 1;
    }

    static boolean secondChannel(Set<Factor> account) {
        return account.contains(Factor.EMAIL) || account.contains(Factor.PHONE) || account.contains(Factor.RECOVERY_CODES);
    }

    static void checkAdministrative(String label, World w, List<String> violations) {
        int accountClass = expectedClass(w.account());
        Set<Factor> c = w.controlled();
        if (w.secondFactors().contains("push")) {
            violations.add(label + " push served an administrator as a second factor");
        }
        if (accountClass >= 3) {
            for (String weak : List.of("email_otp", "sms_otp", "magic_link")) {
                if (w.secondFactors().contains(weak)) {
                    violations.add(label + " " + weak + " satisfied the MFA of an administrator with a strong factor");
                }
            }
            return;
        }
        boolean firstFactor = c.contains(Factor.PASSWORD) || c.contains(Factor.EMAIL) || c.contains(Factor.PHONE);
        boolean weakControlled = c.contains(Factor.EMAIL) || c.contains(Factor.PHONE) || c.contains(Factor.RECOVERY_CODES);
        if (secondChannel(w.account()) && !weakControlled && !w.sessions().isEmpty()) {
            violations.add(label + " the password alone reached the enrollment session of an administrator with a second channel " + w.sessions());
        }
        if (!secondChannel(w.account()) && firstFactor && w.sessions().isEmpty()) {
            violations.add(label + " an administrator without a second channel was locked out of enrollment");
        }
        if (secondChannel(w.account()) && weakControlled && c.contains(Factor.PASSWORD) && w.sessions().isEmpty()) {
            violations.add(label + " password and the second channel did not reach the enrollment session");
        }
    }

    static void check(String label, World w, boolean policyRequired, List<String> violations) {
        int accountClass = expectedClass(w.account());
        if (AssuranceEngine.accountClass(w.account()) != accountClass) {
            violations.add(label + " engine account class " + AssuranceEngine.accountClass(w.account()) + ", expected " + accountClass);
        }
        int capability = capability(w.controlled());
        Set<Factor> c = w.controlled();
        boolean firstFactor = c.contains(Factor.PASSWORD) || c.contains(Factor.EMAIL) || c.contains(Factor.PHONE);
        int max = w.sessions().stream().mapToInt(Sess::strength).max().orElse(-1);
        if (accountClass >= 3 && capability < 3 && !w.sessions().isEmpty()) {
            violations.add(label + " weaker capabilities reached a session " + w.sessions());
        }
        if (capability < accountClass) {
            for (Sess s : w.sessions()) {
                for (Operation op : Operation.values()) {
                    if (AssuranceEngine.permits(op, w.account(), s.strength(), true)) {
                        violations.add(label + " session " + s + " below the account class permits " + op);
                    }
                }
                if (AssuranceEngine.requiredAcr(w.account(), s.strength(), false, false) == null && accountClass >= 3) {
                    violations.add(label + " session " + s + " below the account class satisfies the OIDC acr");
                }
            }
            if (w.remembered()) {
                violations.add(label + " minted a remembered device below the account class");
            }
        }
        if (accountClass >= 3 && !w.sessions().isEmpty() && !policyPair(c)) {
            violations.add(label + " reached a session without two factors or a verified passkey " + w.sessions());
        }
        if (c.contains(Factor.PASSKEY) && max < 4) {
            violations.add(label + " a verified passkey did not reach class 4");
        }
        if (accountClass == 3 && c.contains(Factor.TOTP) && firstFactor && max < 3) {
            violations.add(label + " first factor and TOTP did not reach class 3");
        }
        if (accountClass == 4 && c.contains(Factor.SECURITY_KEY) && c.contains(Factor.PASSWORD) && max < 4) {
            violations.add(label + " security key and password did not reach class 4");
        }
        if (accountClass >= 3 && c.contains(Factor.RECOVERY_CODES) && firstFactor && max < 3) {
            violations.add(label + " a recovery code and a first factor did not recover the account");
        }
        if (accountClass == 1 && !policyRequired && firstFactor && w.sessions().isEmpty()) {
            violations.add(label + " a first factor did not sign in to a class 1 account");
        }
    }

    @Test
    @DisplayName("SSO-T-auth-644 assurance invariant over every account factor set (subsets of password, email, phone, TOTP, verified passkey, security key, recovery codes), every attacker capability set and every transition (password, magic link, email and SMS OTP, social auto-link, passkey, security key, password reset, offered second factors, push approved from reached sessions, step-up, remembered devices, every protected operation, OIDC acr), with push on and off and with no policy MFA, a policy MFA requirement or an administrative account (F-CONF-19: no weak method or push satisfies the MFA of an administrator with a strong factor, an administrator without one reaches the enrollment session only through a second channel when the account has one and is never locked out); the account class comes from the specification, not from the engine (F-CONF-13)")
    void assuranceInvariantForAllFactorSets() {
        List<String> violations = new ArrayList<>();
        int worlds = 0;
        for (Set<Factor> account : subsets(Factor.class)) {
            for (Set<Cap> caps : subsets(Cap.class)) {
                for (boolean push : List.of(false, true)) {
                    for (int mode = 0; mode < 3; mode++) {
                        boolean policyRequired = mode > 0;
                        boolean administrative = mode == 2;
                        for (boolean social : List.of(false, true)) {
                            World w = explore(account, caps, policy(push, policyRequired && !administrative), policyRequired, administrative,
                                    social);
                            String label = account + " " + caps + " push=" + push + " required=" + policyRequired + " admin=" + administrative
                                    + " social=" + social;
                            check(label, w, policyRequired, violations);
                            if (administrative) {
                                checkAdministrative(label, w, violations);
                            }
                            worlds++;
                        }
                    }
                }
            }
        }
        assertEquals(128 * 128 * 12, worlds);
        assertTrue(violations.isEmpty(), violations.size() + " violations\n" + String.join("\n", violations.subList(0, Math.min(40, violations.size()))));
    }

    record Built(String email, String phone, UserEntity user, String secret, SoftAuthenticator passkey, SoftAuthenticator key,
                 List<String> codes) {
    }

    private Optional<CapturingTransport.Message> newest(String destination, String prefix, int before) {
        List<CapturingTransport.Message> list = transport.to(destination);
        for (int i = list.size() - 1; i >= before; i--) {
            if (list.get(i).template() != null && list.get(i).template().startsWith(prefix)) {
                return Optional.of(list.get(i));
            }
        }
        return Optional.empty();
    }

    private String otpCode(Browser b, String channel, String destination) {
        int before = transport.to(destination).size();
        MvcResult r = b.post(BASE + "/otp/request", Map.of("channel", channel, "destination", destination));
        assertEquals(202, status(r), content(r));
        return newest(destination, "otp.", before).map(m -> m.vars().get("code")).orElse(null);
    }

    private MvcResult magicLink(Browser b, String email) {
        int before = transport.to(email).size();
        assertEquals(202, status(b.post(BASE + "/magic-link/request", Map.of("email", email, "redirect", "/"))));
        Optional<CapturingTransport.Message> m = newest(email, "magic", before);
        if (m.isEmpty()) {
            return null;
        }
        Matcher matcher = Pattern.compile("token=([^&]+)").matcher(m.get().vars().get("link"));
        assertTrue(matcher.find());
        return b.post(BASE + "/magic-link/consume", Map.of("token", URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8)));
    }

    private Built build(Set<Factor> account) {
        String email = uniqueEmail("atk10-http");
        UserEntity user = user(email);
        String phone = null;
        if (account.contains(Factor.PHONE)) {
            phone = "+4073" + ThreadLocalRandom.current().nextInt(1000000, 9999999);
            user.setPhoneNumber(phone);
            userRepository.save(user);
        }
        Browser owner = loggedIn(email);
        String secret = null;
        List<String> codes = new ArrayList<>();
        if (account.contains(Factor.TOTP)) {
            Totp t = enrollTotp(owner);
            secret = t.secret();
            codes.addAll(t.recoveryCodes());
        }
        SoftAuthenticator passkey = account.contains(Factor.PASSKEY) ? registerKey(owner, "biometric", SoftAuthenticator.UP | SoftAuthenticator.UV) : null;
        SoftAuthenticator key = account.contains(Factor.SECURITY_KEY) ? registerKey(owner, "security_key", SoftAuthenticator.UP) : null;
        if (account.contains(Factor.RECOVERY_CODES) && codes.isEmpty()) {
            MvcResult r = owner.post(BASE + "/mfa/recovery/regenerate", Map.of());
            assertEquals(200, status(r), content(r));
            json(r).get("recoveryCodes").forEach(n -> codes.add(n.asText()));
        }
        if (!account.contains(Factor.RECOVERY_CODES) && !codes.isEmpty()) {
            assertEquals(204, status(owner.delete(BASE + "/mfa/factors/recovery")));
            codes.clear();
        }
        if (!account.contains(Factor.PASSWORD)) {
            UserEntity u = userRepository.findById(user.getId()).orElseThrow();
            u.setPassword(null);
            userRepository.save(u);
        }
        clock.advance(Duration.ofSeconds(2));
        return new Built(email, phone, user, secret, passkey, key, codes);
    }

    private Object proof(Built a, Set<Factor> c, Browser b, String method, String mfaToken, String password) {
        switch (method) {
            case "totp" -> {
                if (!c.contains(Factor.TOTP)) {
                    return null;
                }
                nextTotpStep();
                return totpCode(a.secret());
            }
            case "recovery" -> {
                return c.contains(Factor.RECOVERY_CODES) && !a.codes().isEmpty() ? a.codes().remove(0) : null;
            }
            case "password" -> {
                return c.contains(Factor.PASSWORD) ? password : null;
            }
            case "email_otp", "sms_otp" -> {
                boolean email = method.equals("email_otp");
                if (!c.contains(email ? Factor.EMAIL : Factor.PHONE)) {
                    return null;
                }
                String destination = email ? a.email() : a.phone();
                if (mfaToken == null) {
                    return otpCode(b, email ? "email" : "sms", destination);
                }
                int before = transport.to(destination).size();
                if (status(b.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", method))) != 200) {
                    return null;
                }
                return newest(destination, "otp.", before).map(m -> m.vars().get("code")).orElse(null);
            }
            case "webauthn" -> {
                SoftAuthenticator device = c.contains(Factor.PASSKEY) ? a.passkey() : c.contains(Factor.SECURITY_KEY) ? a.key() : null;
                if (device == null) {
                    return null;
                }
                int flags = device == a.passkey() ? SoftAuthenticator.UP | SoftAuthenticator.UV : SoftAuthenticator.UP;
                JsonNode options = mfaToken == null ? json(b.post(BASE + "/webauthn/login/options", Map.of("email", a.email())))
                        : json(b.post(BASE + "/mfa/challenge", Map.of("mfaToken", mfaToken, "method", method)));
                return parse(device.assertion(options, flags));
            }
            default -> {
                return null;
            }
        }
    }

    private void finish(Built a, Set<Factor> c, Browser b, MvcResult first, String password, List<Browser> reached) {
        if (first == null || status(first) != 200) {
            return;
        }
        String state = json(first).path("status").asText();
        if ("ok".equals(state)) {
            reached.add(b);
            return;
        }
        if (!"mfa_required".equals(state)) {
            return;
        }
        String token = json(first).get("mfaToken").asText();
        for (String m : methods(first)) {
            Object p = proof(a, c, b, m, token, password);
            if (p == null) {
                continue;
            }
            Map<String, Object> verify = new LinkedHashMap<>();
            verify.put("mfaToken", token);
            verify.put("method", m);
            verify.put(p instanceof JsonNode ? "credential" : "code", p);
            MvcResult r = b.post(BASE + "/mfa/verify", verify);
            if (status(r) == 200 && "ok".equals(json(r).path("status").asText())) {
                reached.add(b);
                return;
            }
        }
    }

    private boolean full(Built a, Set<Factor> c, Browser b, String password) {
        if (status(b.get(BASE + "/test/fresh")) == 200) {
            return true;
        }
        for (String m : List.of("webauthn", "totp", "recovery", "password", "email_otp", "sms_otp")) {
            Object p = proof(a, c, b, m, null, password);
            if (p == null) {
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("method", m);
            body.put(p instanceof JsonNode ? "credential" : "code", p);
            if (status(b.post(BASE + "/step-up", body)) == 200 && status(b.get(BASE + "/test/fresh")) == 200) {
                return true;
            }
        }
        return false;
    }

    private List<Boolean> attackHttp(Built a, Set<Factor> account, Set<Cap> caps) {
        Set<Factor> c = controlled(account, caps);
        List<Browser> reached = new ArrayList<>();
        String password = PASSWORD;
        if (c.contains(Factor.EMAIL)) {
            String uuid = authService.forgotPassword(new ForgotPasswordRequest(a.email())).uuid();
            password = "Rst-" + Bytes.randomToken(6) + "-" + PASSWORD;
            assertEquals(200, status(browser().post(BASE + "/reset-password", Map.of("email", a.email(), "uuid", uuid, "password", password))));
            clock.advance(Duration.ofSeconds(2));
        }
        if (c.contains(Factor.PASSWORD)) {
            Browser b = browser();
            finish(a, c, b, login(b, a.email(), password), password, reached);
        }
        if (c.contains(Factor.EMAIL)) {
            Browser b = browser();
            finish(a, c, b, magicLink(b, a.email()), password, reached);
            Browser o = browser();
            String code = otpCode(o, "email", a.email());
            finish(a, c, o, o.post(BASE + "/otp/verify", Map.of("channel", "email", "destination", a.email(), "code", code)), password, reached);
        }
        if (c.contains(Factor.PHONE)) {
            Browser b = browser();
            String code = otpCode(b, "sms", a.phone());
            finish(a, c, b, b.post(BASE + "/otp/verify", Map.of("channel", "sms", "destination", a.phone(), "code", code)), password, reached);
        }
        if (c.contains(Factor.PASSKEY)) {
            Browser b = browser();
            finish(a, c, b, keyLogin(b, a.email(), a.passkey(), SoftAuthenticator.UP | SoftAuthenticator.UV), password, reached);
        }
        if (c.contains(Factor.SECURITY_KEY)) {
            Browser b = browser();
            finish(a, c, b, keyLogin(b, a.email(), a.key(), SoftAuthenticator.UP), password, reached);
        }
        boolean full = false;
        for (Browser b : reached) {
            if (full(a, c, b, password)) {
                full = true;
                break;
            }
        }
        return List.of(!reached.isEmpty(), full);
    }

    @Test
    @DisplayName("SSO-T-auth-645 sampled HTTP pass of the assurance model: for a deterministic sample of account factor sets and attacker capabilities, the real sign-in, MFA, password reset and step-up endpoints reach a session, and full factor management, exactly when the in-memory model of SSO-T-auth-644 predicts it")
    void httpLayerMatchesTheModel() {
        boolean push = properties.getPush().isEnabled();
        boolean risk = properties.getRisk().isEnabled();
        properties.getPush().setEnabled(false);
        properties.getRisk().setEnabled(false);
        try {
            List<Set<Factor>> accounts = subsets(Factor.class).stream().filter(s -> s.contains(Factor.EMAIL)).toList();
            List<Set<Cap>> capSets = subsets(Cap.class);
            List<Map.Entry<Set<Factor>, Set<Cap>>> sample = new ArrayList<>();
            sample.add(Map.entry(EnumSet.of(Factor.PASSWORD, Factor.EMAIL, Factor.SECURITY_KEY), EnumSet.of(Cap.SECURITY_KEY)));
            sample.add(Map.entry(EnumSet.of(Factor.PASSWORD, Factor.EMAIL, Factor.SECURITY_KEY), EnumSet.of(Cap.SECURITY_KEY, Cap.PASSWORD)));
            sample.add(Map.entry(EnumSet.of(Factor.EMAIL, Factor.SECURITY_KEY), EnumSet.of(Cap.SECURITY_KEY)));
            sample.add(Map.entry(EnumSet.of(Factor.PASSWORD, Factor.EMAIL, Factor.PHONE, Factor.TOTP, Factor.RECOVERY_CODES),
                    EnumSet.of(Cap.PASSWORD, Cap.MAILBOX, Cap.SIM)));
            sample.add(Map.entry(EnumSet.of(Factor.PASSWORD, Factor.EMAIL, Factor.PASSKEY, Factor.RECOVERY_CODES),
                    EnumSet.of(Cap.PASSWORD, Cap.RECOVERY_CODE)));
            sample.add(Map.entry(EnumSet.of(Factor.EMAIL, Factor.PASSKEY), EnumSet.of(Cap.PASSKEY_DEVICE)));
            sample.add(Map.entry(EnumSet.of(Factor.PASSWORD, Factor.EMAIL, Factor.PHONE), EnumSet.of(Cap.SIM)));
            for (int i = 0; i < 33; i++) {
                sample.add(Map.entry(accounts.get((i * 29 + 3) % accounts.size()), capSets.get((i * 71 + 5) % capSets.size())));
            }
            SsoClientPolicy.Policy policy = policy(false, false);
            List<String> mismatches = new ArrayList<>();
            for (Map.Entry<Set<Factor>, Set<Cap>> e : sample) {
                World w = explore(e.getKey(), e.getValue(), policy, false, false, false);
                boolean any = !w.sessions().isEmpty();
                boolean managed = w.sessions().stream().anyMatch(s -> AssuranceEngine.permits(Operation.MANAGE_FACTORS, w.account(), s.strength(), true));
                List<Boolean> actual = attackHttp(build(e.getKey()), e.getKey(), e.getValue());
                if (actual.get(0) != any || actual.get(1) != managed) {
                    mismatches.add(e.getKey() + " " + e.getValue() + " model session=" + any + " full=" + managed + " http session=" + actual.get(0)
                            + " full=" + actual.get(1));
                }
            }
            assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
        } finally {
            properties.getPush().setEnabled(push);
            properties.getRisk().setEnabled(risk);
        }
    }
}
