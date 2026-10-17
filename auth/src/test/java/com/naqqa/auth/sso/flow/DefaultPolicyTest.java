package com.naqqa.auth.sso.flow;

import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.admin.SsoClientSettingsStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultPolicyTest {

    static SsoProperties properties() {
        SsoProperties p = new SsoProperties();
        p.setEnabled(true);
        return p;
    }

    static SsoClientPolicy.Policy policy(SsoProperties p) {
        return new SsoClientPolicy(p, new DefaultListableBeanFactory().getBeanProvider(SsoClientSettingsStore.class)).forClient(null);
    }

    @Test
    @DisplayName("SSO-T-auth-746 F-CONF-16 fresh install defaults: password, TOTP and recovery codes are enabled and every account holding an administrative role or authority needs MFA; without a factor it signs in restricted to enrollment, with TOTP it is challenged, a plain account is unaffected, and nothing restricts when the operator disables every enrollable factor")
    void freshInstallAdministratorPolicy() {
        SsoProperties p = properties();
        SsoClientPolicy.Policy policy = policy(p);
        assertTrue(policy.password());
        assertTrue(AssuranceEngine.enabled(policy, SecondFactors.TOTP));
        assertTrue(AssuranceEngine.enabled(policy, SecondFactors.RECOVERY));
        for (String method : List.of(SecondFactors.WEBAUTHN, SecondFactors.EMAIL_OTP, SecondFactors.SMS_OTP, SecondFactors.PUSH,
                AssuranceEngine.MAGIC_LINK)) {
            assertFalse(AssuranceEngine.enabled(policy, method), method);
        }
        SsoProperties.Mfa mfa = p.getMfa();
        assertTrue(mfa.isRequiredForAdmin());
        assertFalse(AssuranceEngine.mfaRequiredByPolicy(policy, Set.of("ADMIN")));
        assertTrue(AssuranceEngine.administrative(Set.of("ADMIN"), Set.of(), mfa));
        for (String authority : List.of("SSO_ADMIN_USERS", "SSO_ADMIN_TENANTS", "SSO_IMPERSONATE", "SSO_AUDIT_EXPORT", "users:update",
                "roles:create", "sub_roles:delete")) {
            assertTrue(AssuranceEngine.administrative(Set.of("STAFF"), Set.of(authority), mfa), authority);
        }
        for (String authority : List.of("users:read", "roles:read", "profile:read", "SSO_ADMIN")) {
            assertFalse(AssuranceEngine.administrative(Set.of("USER"), Set.of(authority), mfa), authority);
        }

        Set<AssuranceEngine.Factor> admin = EnumSet.of(AssuranceEngine.Factor.PASSWORD, AssuranceEngine.Factor.EMAIL);
        AssuranceEngine.SignIn flagged = AssuranceEngine.signIn(admin, List.of("pwd"), policy, policy, Set.of(), true, false, false);
        assertEquals(AssuranceEngine.Outcome.COMPLETE, flagged.outcome());
        assertTrue(flagged.enrollmentRequired());
        assertEquals(AssuranceEngine.KNOWLEDGE, flagged.strength());
        assertTrue(AssuranceEngine.enrollmentRestricted(admin, policy, true));
        assertFalse(AssuranceEngine.enrollmentRestricted(admin, policy, false));

        Set<AssuranceEngine.Factor> enrolled = EnumSet.of(AssuranceEngine.Factor.PASSWORD, AssuranceEngine.Factor.EMAIL,
                AssuranceEngine.Factor.TOTP, AssuranceEngine.Factor.RECOVERY_CODES);
        AssuranceEngine.SignIn challenged = AssuranceEngine.signIn(enrolled, List.of("pwd"), policy, policy, Set.of(), true, false, false);
        assertEquals(AssuranceEngine.Outcome.SECOND_FACTOR, challenged.outcome());
        assertTrue(challenged.methods().contains(SecondFactors.TOTP));
        assertFalse(AssuranceEngine.enrollmentRestricted(enrolled, policy, true));

        AssuranceEngine.SignIn plain = AssuranceEngine.signIn(admin, List.of("pwd"), policy, policy, Set.of(), false, false, false);
        assertEquals(AssuranceEngine.Outcome.COMPLETE, plain.outcome());
        assertFalse(plain.enrollmentRequired());

        SsoProperties off = properties();
        off.getMfa().setRequiredForAdmin(false);
        assertFalse(AssuranceEngine.administrative(Set.of("ADMIN"), Set.of("SSO_ADMIN_USERS"), off.getMfa()));
        SsoProperties nothing = properties();
        nothing.getTotp().setEnabled(false);
        assertFalse(AssuranceEngine.enrollmentRestricted(admin, policy(nothing), true));
    }
}
