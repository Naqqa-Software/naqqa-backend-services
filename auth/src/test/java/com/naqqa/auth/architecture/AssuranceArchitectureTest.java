package com.naqqa.auth.architecture;

import com.naqqa.auth.AuthApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AssuranceArchitectureTest {

    static final String ENGINE = "com/naqqa/auth/sso/flow/AssuranceEngine";
    static final String SECOND_FACTORS = "com/naqqa/auth/sso/flow/SecondFactors";
    static final String SESSIONS = "com/naqqa/auth/sso/flow/SsoSessionManager";
    static final String POLICY = "com/naqqa/auth/sso/flow/SsoClientPolicy";
    static final Set<String> POLICY_FACTOR_SWITCHES = Set.of("password", "magicLink", "emailOtp", "smsOtp", "totp", "recovery", "passkey",
            "biometric", "securityKey", "push", "webauthn", "mfaRequiredForAll", "mfaRequiredRoles");
    static final Set<String> STRING_COMPARISONS = Set.of("equals", "hashCode", "equalsIgnoreCase", "contentEquals", "compareTo",
            "compareToIgnoreCase", "regionMatches", "matches");
    static final Set<String> CASE_FOLDING = Set.of("equalsIgnoreCase", "compareToIgnoreCase", "regionMatches", "toLowerCase", "toUpperCase");
    static final Set<String> COLLECTION_LOOKUPS = Set.of("contains", "containsKey", "containsAll", "indexOf", "get", "getOrDefault");
    static final Set<String> COLLECTION_FACTORIES = Set.of("of", "asList", "copyOf");
    static final Set<String> LOOKUP_EXEMPT = Set.of("AccountExportService#export", "MfaController#enroll", "MfaController#hotpEnroll");
    static final Set<String> SWITCH_TABLES = Set.of("com/naqqa/auth/sso/admin/SsoClientSettingsStore.METHODS");
    static final Set<String> METHOD_IDS = Set.of("totp", "webauthn", "recovery", "email_otp", "sms_otp", "push", "magic_link");
    static final Map<String, Set<String>> FACTOR_STATE = Map.of(
            "com/naqqa/sso/mfa/TotpEnrollment", Set.of("isActive"),
            "com/naqqa/auth/sso/flow/HotpEnrollment", Set.of("isActive"),
            "com/naqqa/sso/mfa/RecoveryCodes", Set.of("remaining"),
            "com/naqqa/auth/sso/flow/WebAuthnService", Set.of("hasCredentials"),
            SESSIONS, Set.of("authStrength", "raiseStrength", "recordAuthentication"));
    static final Set<String> FACTOR_LISTINGS = Set.of("MfaController#factors", "MfaController#delete", "UserAdminController#view",
            "UserAdminController#resetFactors");
    static final List<String> DECISIONS = List.of(
            "com/naqqa/auth/sso/flow/SsoLoginFlow#login", "com/naqqa/auth/sso/flow/SsoLoginFlow#afterFirstFactor",
            "com/naqqa/auth/sso/flow/SsoLoginFlow#complete",
            "com/naqqa/auth/sso/web/MfaController#verify", "com/naqqa/auth/sso/web/MfaController#challenge",
            "com/naqqa/auth/sso/web/MfaController#enroll", "com/naqqa/auth/sso/web/MfaController#confirm",
            "com/naqqa/auth/sso/web/MfaController#hotpEnroll", "com/naqqa/auth/sso/web/MfaController#hotpConfirm",
            "com/naqqa/auth/sso/web/MfaController#regenerate",
            "com/naqqa/auth/sso/web/StepUpController#stepUp",
            "com/naqqa/auth/sso/web/WebAuthnController#registerOptions", "com/naqqa/auth/sso/web/WebAuthnController#registerVerify",
            "com/naqqa/auth/sso/web/WebAuthnController#loginOptions", "com/naqqa/auth/sso/web/WebAuthnController#loginVerify",
            "com/naqqa/auth/sso/web/OtpController#request", "com/naqqa/auth/sso/web/OtpController#verify",
            "com/naqqa/auth/sso/web/MagicLinkController#request", "com/naqqa/auth/sso/web/MagicLinkController#consume",
            "com/naqqa/auth/sso/social/SocialLoginController#login",
            "com/naqqa/auth/sso/saml/SamlSpAccounts#signIn", "com/naqqa/auth/sso/saml/SamlSpAccounts#resolve",
            "com/naqqa/auth/sso/web/PushController#respond",
            "com/naqqa/auth/sso/web/AccountController#delete",
            "com/naqqa/auth/sso/web/ImpersonationController#start",
            "com/naqqa/auth/sso/oidc/OidcProviderConfiguration#withPolicyAcr",
            "com/naqqa/auth/sso/saml/SamlIdpController#decide",
            "com/naqqa/auth/security/StepUpInterceptor#preHandle");

    static Bytecode code;

    @BeforeAll
    static void load() {
        code = Bytecode.of(AuthApplication.class, "com/naqqa/auth");
    }

    static String outer(String owner) {
        int i = owner.indexOf('$');
        return i < 0 ? owner : owner.substring(0, i);
    }

    static String simple(String owner) {
        String o = outer(owner);
        return o.substring(o.lastIndexOf('/') + 1);
    }

    static boolean comparison(Bytecode.Ref c, boolean literalCollection) {
        if (c.owner().equals("java/lang/String")) {
            return STRING_COMPARISONS.contains(c.name());
        }
        if (c.owner().equals("java/util/Objects")) {
            return c.name().equals("equals");
        }
        return literalCollection && c.owner().startsWith("java/util/") && COLLECTION_LOOKUPS.contains(c.name());
    }

    static Set<String> idTables() {
        Set<String> out = new java.util.LinkedHashSet<>();
        code.fieldInits().forEach((field, init) -> {
            boolean collection = init.calls().stream().anyMatch(c -> c.owner().startsWith("java/util/"));
            boolean ids = init.strings().stream().anyMatch(s -> METHOD_IDS.contains(s.toLowerCase(Locale.ROOT)));
            if (collection && ids && !SWITCH_TABLES.contains(field) && !outer(field.substring(0, field.lastIndexOf('.'))).equals(ENGINE)) {
                out.add(field);
            }
        });
        return out;
    }

    @Test
    @DisplayName("SSO-T-auth-643 the assurance model has one implementation: only AssuranceEngine derives levels or acr from amr, reads the client's factor switches, reads enrolled factor state or session strength for a decision, compares second-factor method ids (inline, or through a method-id table held in a field, F-CONF-28), or declares a strength rule; every sign-in (SAML SP included), MFA, step-up, enrollment, push, erasure, impersonation, OIDC, SAML and step-up guard decision reaches it")
    void assuranceDecisionsLiveInTheEngine() {
        List<String> problems = new ArrayList<>();
        Set<String> idTables = idTables();
        for (Bytecode.Info m : code.methods()) {
            String owner = outer(m.ref.owner());
            if (owner.equals(ENGINE)) {
                continue;
            }
            String where = simple(m.ref.owner()) + "#" + m.ref.name();
            boolean lambda = m.ref.name().startsWith("lambda$");
            String declaring = lambda ? simple(m.ref.owner()) + "#" + m.ref.name().split("\\$")[1] : where;
            for (Bytecode.Ref c : m.calls) {
                if (c.owner().equals("com/naqqa/sso/mfa/StepUp") && Set.of("level", "acr", "levelOfAcr", "evaluate").contains(c.name())) {
                    problems.add(where + " derives assurance with StepUp." + c.name() + " outside AssuranceEngine");
                }
                if (c.owner().equals(POLICY + "$Policy") && POLICY_FACTOR_SWITCHES.contains(c.name()) && !owner.equals(POLICY)) {
                    problems.add(where + " reads the client switch " + c.name() + "() outside AssuranceEngine");
                }
                Set<String> state = FACTOR_STATE.get(c.owner());
                if (state != null && state.contains(c.name()) && !owner.equals(c.owner()) && !FACTOR_LISTINGS.contains(declaring)) {
                    problems.add(where + " reads " + simple(c.owner()) + "." + c.name() + " outside AssuranceEngine");
                }
            }
            boolean literalCollection = !LOOKUP_EXEMPT.contains(declaring) && !owner.equals(POLICY)
                    && m.calls.stream().anyMatch(c -> c.owner().startsWith("java/util/") && COLLECTION_FACTORIES.contains(c.name()));
            boolean compares = m.calls.stream().anyMatch(c -> comparison(c, literalCollection));
            boolean folds = m.calls.stream().anyMatch(c -> c.owner().equals("java/lang/String") && CASE_FOLDING.contains(c.name()));
            boolean methodIds = m.strings.stream().anyMatch(s -> METHOD_IDS.contains(folds ? s.toLowerCase(Locale.ROOT) : s));
            if (compares && methodIds && !owner.equals(SECOND_FACTORS) && !FACTOR_LISTINGS.contains(declaring)) {
                problems.add(where + " compares second-factor method ids outside AssuranceEngine");
            }
            boolean tableLookup = m.calls.stream().anyMatch(c -> comparison(c, true));
            for (String field : m.fieldsRead) {
                if (idTables.contains(field) && tableLookup && !owner.equals(SECOND_FACTORS) && !owner.equals(POLICY)
                        && !LOOKUP_EXEMPT.contains(declaring) && !FACTOR_LISTINGS.contains(declaring)) {
                    problems.add(where + " looks up second-factor method ids in the table " + field + " outside AssuranceEngine");
                }
            }
            if (m.ref.name().toLowerCase(Locale.ROOT).contains("strength") && !owner.equals(SESSIONS)) {
                problems.add(where + " declares a strength rule outside AssuranceEngine");
            }
        }
        for (String d : DECISIONS) {
            String owner = d.substring(0, d.indexOf('#'));
            String name = d.substring(d.indexOf('#') + 1);
            List<Bytecode.Info> found = new ArrayList<>();
            for (Bytecode.Info m : code.methods()) {
                if (m.ref.owner().equals(owner) && m.ref.name().equals(name)) {
                    found.add(m);
                }
            }
            if (found.isEmpty()) {
                problems.add(d + " not found");
            }
            for (Bytecode.Info m : found) {
                if (!code.reaches(m.ref, 4, r -> r.owner().equals(ENGINE))) {
                    problems.add(simple(owner) + "#" + name + m.ref.desc() + " decides without AssuranceEngine");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
