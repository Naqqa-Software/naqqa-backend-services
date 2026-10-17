package com.naqqa.auth.sso.flow;

import com.naqqa.auth.entity.auth.UserEntity;
import com.naqqa.auth.security.AuthPrincipal;
import com.naqqa.auth.security.StepUpFactorPolicy;
import com.naqqa.auth.service.security.TokenService;
import com.naqqa.auth.sso.SsoProperties;
import com.naqqa.auth.sso.ldap.DirectoryLogin;
import com.naqqa.sso.mfa.StepUp;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "naqqa.sso", name = "enabled", havingValue = "true")
public class AssuranceEngine implements StepUpFactorPolicy {

    public enum Factor { PASSWORD, EMAIL, PHONE, TOTP, PASSKEY, SECURITY_KEY, RECOVERY_CODES }

    public enum Operation {
        MANAGE_FACTORS(true), CHANGE_EMAIL(true), CHANGE_PASSWORD(true), ERASE(true), EXPORT(true), LINK_IDENTITY(true),
        IMPERSONATE(true), ADMIN(true), API_TOKEN(true), REGISTER_PASSKEY(true),
        APPROVE_PUSH(false), REMEMBER_DEVICE(false), OIDC_GRANT(false), OIDC_OFFLINE_GRANT(false), DEVICE_APPROVAL(false);

        final boolean fresh;

        Operation(boolean fresh) {
            this.fresh = fresh;
        }

        public boolean needsFreshAuthentication() {
            return fresh;
        }
    }

    public enum Outcome { COMPLETE, SECOND_FACTOR, REFUSE }

    public record SignIn(Outcome outcome, List<String> methods, int strength, boolean enrollmentRequired) {
    }

    public record AcrRequirement(String minAcr, Set<String> allowedAcr) {
    }

    public static final String MAGIC_LINK = "magic_link";
    public static final String ENROLLMENT_CLAIM = TokenService.ENROLLMENT_CLAIM;

    public static final int NONE = 0;
    public static final int KNOWLEDGE = 1;
    public static final int POSSESSION_WEAK = 2;
    public static final int POSSESSION_STRONG = 3;
    public static final int PHISHING_RESISTANT = 4;

    static final List<String> SECOND_FACTOR_ORDER = List.of(SecondFactors.TOTP, SecondFactors.WEBAUTHN, SecondFactors.RECOVERY,
            SecondFactors.EMAIL_OTP, SecondFactors.SMS_OTP, SecondFactors.PUSH, SecondFactors.PASSWORD);
    static final Set<String> ENROLLED_FALLBACK = Set.of(SecondFactors.TOTP, SecondFactors.WEBAUTHN, SecondFactors.RECOVERY, SecondFactors.PASSWORD);

    private final SecondFactors factors;
    private final WebAuthnService webauthn;
    private final SsoSessionManager sessions;
    private final ObjectProvider<DirectoryLogin> directories;

    public AssuranceEngine(SecondFactors factors, WebAuthnService webauthn, SsoSessionManager sessions,
                           ObjectProvider<DirectoryLogin> directories) {
        this.factors = factors;
        this.webauthn = webauthn;
        this.sessions = sessions;
        this.directories = directories;
    }

    public static int accountClass(Set<Factor> enrolled) {
        if (enrolled.contains(Factor.PASSKEY) || enrolled.contains(Factor.SECURITY_KEY)) {
            return PHISHING_RESISTANT;
        }
        return enrolled.contains(Factor.TOTP) ? POSSESSION_STRONG : KNOWLEDGE;
    }

    public static int methodStrength(String method) {
        return switch (method == null ? "" : method) {
            case SecondFactors.WEBAUTHN -> PHISHING_RESISTANT;
            case SecondFactors.TOTP, SecondFactors.RECOVERY, SecondFactors.PUSH -> POSSESSION_STRONG;
            case SecondFactors.EMAIL_OTP, SecondFactors.SMS_OTP, MAGIC_LINK -> POSSESSION_WEAK;
            case SecondFactors.PASSWORD -> KNOWLEDGE;
            default -> NONE;
        };
    }

    static boolean webauthnAmr(Collection<String> amr) {
        return amr.contains("hwk") || amr.contains("swk");
    }

    static boolean userVerified(Collection<String> amr) {
        return amr.contains("user") || amr.contains("face") || amr.contains("fpt") || amr.contains("pin");
    }

    static boolean otherFactor(Collection<String> amr) {
        return amr.contains("pwd") || amr.contains("fed") || amr.contains("otp") || amr.contains("sms") || amr.contains("mca");
    }

    public static boolean possessionOnly(Collection<String> amr) {
        return amr != null && webauthnAmr(amr) && !userVerified(amr) && !otherFactor(amr);
    }

    public static int amrStrength(Collection<String> amr) {
        if (amr == null) {
            return NONE;
        }
        if (webauthnAmr(amr) && (userVerified(amr) || otherFactor(amr))) {
            return PHISHING_RESISTANT;
        }
        if (amr.contains("mca") || upstreamMfa(amr)) {
            return POSSESSION_STRONG;
        }
        if (amr.contains("otp") || amr.contains("sms")) {
            return POSSESSION_WEAK;
        }
        return amr.contains("pwd") || amr.contains("fed") || webauthnAmr(amr) ? KNOWLEDGE : NONE;
    }

    static boolean upstreamMfa(Collection<String> amr) {
        return amr.contains("fed") && amr.contains("mfa") && !webauthnAmr(amr) && !amr.contains("otp") && !amr.contains("sms")
                && !amr.contains("pwd");
    }

    public static List<String> federatedAmr(boolean trustedForAssurance, Collection<String> mfaContexts, String authnContextClassRef) {
        if (trustedForAssurance && authnContextClassRef != null && mfaContexts != null && mfaContexts.contains(authnContextClassRef)) {
            return List.of("fed", "mfa");
        }
        return List.of("fed");
    }

    public static int combinedStrength(Collection<String> firstAmr, String method) {
        int first = amrStrength(firstAmr);
        int second = methodStrength(method);
        if (possessionOnly(firstAmr) && second > NONE && !SecondFactors.WEBAUTHN.equals(method)) {
            return PHISHING_RESISTANT;
        }
        return Math.max(first, second);
    }

    public static int stepUpStrength(Collection<String> sessionAmr, String method, Collection<String> verifiedAmr) {
        if (SecondFactors.WEBAUTHN.equals(method) && possessionOnly(verifiedAmr)) {
            return sessionAmr != null && otherFactor(sessionAmr) ? PHISHING_RESISTANT : KNOWLEDGE;
        }
        return methodStrength(method);
    }

    public static int level(Collection<String> amr) {
        return StepUp.level(new LinkedHashSet<>(amr == null ? List.of() : amr));
    }

    public static String acr(Collection<String> amr) {
        return StepUp.acr(new LinkedHashSet<>(amr == null ? List.of() : amr));
    }

    public static int levelOfAcr(String acr) {
        return StepUp.levelOfAcr(acr);
    }

    public static boolean firstFactorCompletes(Collection<String> firstAmr) {
        return level(firstAmr) >= 2;
    }

    public static boolean enabled(SsoClientPolicy.Policy policy, String method) {
        return switch (method == null ? "" : method) {
            case SecondFactors.PASSWORD -> policy.password();
            case MAGIC_LINK -> policy.magicLink();
            case SecondFactors.EMAIL_OTP -> policy.emailOtp();
            case SecondFactors.SMS_OTP -> policy.smsOtp();
            case SecondFactors.TOTP -> policy.totp();
            case SecondFactors.RECOVERY -> policy.recovery();
            case SecondFactors.PUSH -> policy.push();
            case SecondFactors.WEBAUTHN -> policy.webauthn();
            case WebAuthnService.PASSKEY -> policy.passkey();
            case WebAuthnService.BIOMETRIC -> policy.biometric();
            case WebAuthnService.SECURITY_KEY -> policy.securityKey();
            default -> false;
        };
    }

    public static boolean credentialKindAccepted(SsoClientPolicy.Policy policy, String kind) {
        return switch (kind == null ? "" : kind) {
            case WebAuthnService.BIOMETRIC -> policy.biometric() || policy.passkey();
            case WebAuthnService.SECURITY_KEY -> policy.securityKey();
            default -> policy.passkey();
        };
    }

    public static boolean mfaRequiredByPolicy(SsoClientPolicy.Policy policy, Set<String> roles) {
        return policy.mfaRequiredForAll() || roles.stream().anyMatch(policy.mfaRequiredRoles()::contains);
    }

    public static boolean administrative(Collection<String> roles, Collection<String> authorities, SsoProperties.Mfa mfa) {
        if (mfa == null || !mfa.isRequiredForAdmin()) {
            return false;
        }
        if (roles != null && mfa.getAdminRoles() != null && roles.stream().anyMatch(mfa.getAdminRoles()::contains)) {
            return true;
        }
        return authorities != null && mfa.getAdminAuthorities() != null
                && authorities.stream().anyMatch(a -> a != null && mfa.getAdminAuthorities().stream().anyMatch(p -> authorityMatches(p, a)));
    }

    static boolean authorityMatches(String pattern, String authority) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        return pattern.endsWith("*") ? authority.startsWith(pattern.substring(0, pattern.length() - 1)) : pattern.equals(authority);
    }

    public static boolean hasEnrolledFactor(Set<Factor> enrolled) {
        return accountClass(enrolled) >= POSSESSION_STRONG;
    }

    public static boolean enrollable(SsoClientPolicy.Policy policy) {
        return policy != null && (policy.totp() || policy.webauthn());
    }

    public static boolean enrollmentRestricted(Set<Factor> enrolled, SsoClientPolicy.Policy policy, boolean policyRequired) {
        return policyRequired && !hasEnrolledFactor(enrolled) && enrollable(policy);
    }

    public boolean enrollmentPending(AuthPrincipal principal) {
        if (principal == null) {
            return false;
        }
        String sid = principal.getSessionId();
        if (sid != null) {
            return sessions.enrollmentRequired(sid);
        }
        return Boolean.TRUE.equals(principal.getClaims().get(ENROLLMENT_CLAIM));
    }

    public boolean sessionRestricted(String sessionId) {
        return sessionId != null && sessions.enrollmentRequired(sessionId);
    }

    public void restrictSession(String sessionId) {
        sessions.requireEnrollment(sessionId);
    }

    public void releaseSession(String sessionId) {
        sessions.releaseEnrollment(sessionId);
    }

    static boolean enrolledFor(Set<Factor> enrolled, String method) {
        return switch (method) {
            case SecondFactors.TOTP -> enrolled.contains(Factor.TOTP);
            case SecondFactors.WEBAUTHN -> enrolled.contains(Factor.PASSKEY) || enrolled.contains(Factor.SECURITY_KEY);
            case SecondFactors.RECOVERY -> enrolled.contains(Factor.RECOVERY_CODES);
            case SecondFactors.EMAIL_OTP -> enrolled.contains(Factor.EMAIL);
            case SecondFactors.SMS_OTP -> enrolled.contains(Factor.PHONE);
            case SecondFactors.PASSWORD -> enrolled.contains(Factor.PASSWORD);
            case SecondFactors.PUSH -> true;
            default -> false;
        };
    }

    static String firstFactorMethod(Collection<String> firstAmr) {
        if (webauthnAmr(firstAmr)) {
            return SecondFactors.WEBAUTHN;
        }
        if (firstAmr.contains("sms")) {
            return SecondFactors.SMS_OTP;
        }
        if (firstAmr.contains("otp")) {
            return SecondFactors.EMAIL_OTP;
        }
        return firstAmr.contains("pwd") ? SecondFactors.PASSWORD : null;
    }

    public static boolean weakChannel(String method) {
        return SecondFactors.EMAIL_OTP.equals(method) || SecondFactors.SMS_OTP.equals(method) || SecondFactors.PUSH.equals(method)
                || MAGIC_LINK.equals(method);
    }

    static boolean administrativeAccepts(Set<Factor> enrolled, String method) {
        if (hasEnrolledFactor(enrolled)) {
            return !weakChannel(method);
        }
        return !SecondFactors.PUSH.equals(method);
    }

    public static boolean acceptsSecondFactor(Set<Factor> enrolled, Collection<String> firstAmr, String method, boolean administrative) {
        return acceptsSecondFactor(enrolled, firstAmr, method) && (!administrative || administrativeAccepts(enrolled, method));
    }

    public static boolean acceptsSecondFactor(Set<Factor> enrolled, Collection<String> firstAmr, String method) {
        if (method == null || !SECOND_FACTOR_ORDER.contains(method) || !enrolledFor(enrolled, method)) {
            return false;
        }
        Collection<String> first = firstAmr == null ? List.of() : firstAmr;
        if (method.equals(firstFactorMethod(first))) {
            return false;
        }
        if (SecondFactors.PASSWORD.equals(method)) {
            return possessionOnly(first);
        }
        return methodStrength(method) >= Math.min(accountClass(enrolled), POSSESSION_STRONG);
    }

    static List<String> offered(SsoClientPolicy.Policy policy, Set<Factor> enrolled, Collection<String> firstAmr, Set<String> excluded,
                                boolean administrative) {
        List<String> out = new ArrayList<>();
        for (String method : SECOND_FACTOR_ORDER) {
            if ((policy == null ? ENROLLED_FALLBACK.contains(method) : enabled(policy, method)) && !excluded.contains(method)
                    && acceptsSecondFactor(enrolled, firstAmr, method, administrative)) {
                out.add(method);
            }
        }
        return out;
    }

    public static List<String> secondFactors(Set<Factor> enrolled, Collection<String> firstAmr, SsoClientPolicy.Policy policy,
                                             SsoClientPolicy.Policy global, Set<String> excluded, boolean required) {
        return secondFactors(enrolled, firstAmr, policy, global, excluded, required, false);
    }

    public static List<String> secondFactors(Set<Factor> enrolled, Collection<String> firstAmr, SsoClientPolicy.Policy policy,
                                             SsoClientPolicy.Policy global, Set<String> excluded, boolean required, boolean administrative) {
        List<String> methods = offered(policy, enrolled, firstAmr, excluded, administrative);
        if (required && methods.isEmpty()) {
            methods = offered(null, enrolled, firstAmr, excluded, administrative);
        }
        if (required && methods.isEmpty() && global != null && global != policy) {
            methods = offered(global, enrolled, firstAmr, excluded, administrative);
        }
        return methods;
    }

    public static SignIn signIn(Set<Factor> enrolled, Collection<String> firstAmr, SsoClientPolicy.Policy policy, SsoClientPolicy.Policy global,
                                Set<String> excluded, boolean policyRequired, boolean riskStepUp, boolean remembered) {
        return signIn(enrolled, firstAmr, policy, global, excluded, policyRequired, false, riskStepUp, remembered);
    }

    public static SignIn signIn(Set<Factor> enrolled, Collection<String> firstAmr, SsoClientPolicy.Policy policy, SsoClientPolicy.Policy global,
                                Set<String> excluded, boolean policyRequired, boolean administrative, boolean riskStepUp, boolean remembered) {
        int accountClass = accountClass(enrolled);
        boolean secondChannel = administrative && !hasEnrolledFactor(enrolled);
        boolean required = !firstFactorCompletes(firstAmr) && (accountClass > POSSESSION_WEAK || policyRequired || administrative || riskStepUp);
        if (required && !riskStepUp && remembered && !secondChannel) {
            required = false;
        }
        List<String> methods = secondFactors(enrolled, firstAmr, policy, global, excluded, required, administrative);
        if (required && !methods.isEmpty()) {
            return new SignIn(Outcome.SECOND_FACTOR, methods, NONE, false);
        }
        if (required && accountClass > POSSESSION_WEAK) {
            return new SignIn(Outcome.REFUSE, List.of(), NONE, false);
        }
        return new SignIn(Outcome.COMPLETE, List.of(), amrStrength(firstAmr), required);
    }

    public static boolean stepUpAccepted(Set<Factor> enrolled, String method) {
        return methodStrength(method) > NONE && methodStrength(method) >= accountClass(enrolled);
    }

    public static boolean permits(Operation operation, Set<Factor> enrolled, int sessionStrength, boolean fresh) {
        if (operation.needsFreshAuthentication() && !fresh) {
            return false;
        }
        return sessionStrength >= accountClass(enrolled);
    }

    public static boolean mayAutoLink(Set<Factor> enrolled) {
        return accountClass(enrolled) <= POSSESSION_WEAK;
    }

    public static String requiredAcr(Set<Factor> enrolled, int sessionStrength, boolean remembered, boolean secondFactorRequired) {
        int accountClass = accountClass(enrolled);
        if (accountClass > POSSESSION_WEAK && !remembered && sessionStrength < accountClass) {
            return accountClass >= PHISHING_RESISTANT ? StepUp.ACR_PHISHING_RESISTANT : StepUp.ACR_MFA;
        }
        return secondFactorRequired ? StepUp.ACR_MFA : null;
    }

    public static Optional<AcrRequirement> clientAcr(String minAcr, Set<String> allowedAcr, String needed) {
        if (needed == null || levelOfAcr(minAcr) >= levelOfAcr(needed)) {
            return Optional.empty();
        }
        int level = levelOfAcr(needed);
        if (allowedAcr.isEmpty()) {
            return Optional.of(new AcrRequirement(needed, allowedAcr));
        }
        Optional<String> strong = allowedAcr.stream().filter(a -> levelOfAcr(a) >= level).min(Comparator.comparingInt(AssuranceEngine::levelOfAcr));
        if (strong.isPresent()) {
            return Optional.of(new AcrRequirement(strong.get(), allowedAcr));
        }
        Set<String> allowed = new LinkedHashSet<>(allowedAcr);
        allowed.add(needed);
        return Optional.of(new AcrRequirement(needed, allowed));
    }

    public static boolean clientAcrAtMaximum(String minAcr) {
        return levelOfAcr(minAcr) >= 3;
    }

    public static boolean acrSatisfied(String sessionAcr, Collection<String> sessionAmr, String requiredAcr) {
        return requiredAcr == null || requiredAcr.isBlank() || levelOfAcr(sessionAcr) >= levelOfAcr(requiredAcr)
                || level(sessionAmr) >= levelOfAcr(requiredAcr);
    }

    public static boolean reachesLevel(Collection<String> sessionAmr, int requiredLevel) {
        return level(sessionAmr) >= requiredLevel;
    }

    public Set<Factor> enrolled(String userId) {
        Set<Factor> out = EnumSet.noneOf(Factor.class);
        if (factors.totp().isActive(userId) || factors.hotp().isActive(userId)) {
            out.add(Factor.TOTP);
        }
        if (webauthn.configured() && webauthn.hasCredentials(userId)) {
            for (WebAuthnService.Credential c : webauthn.list(userId)) {
                out.add(WebAuthnService.SECURITY_KEY.equals(c.kind()) ? Factor.SECURITY_KEY : Factor.PASSKEY);
            }
        }
        if (factors.recovery().remaining(userId) > 0) {
            out.add(Factor.RECOVERY_CODES);
        }
        return out;
    }

    public Set<Factor> enrolled(UserEntity user) {
        String userId = String.valueOf(user.getId());
        Set<Factor> out = enrolled(userId);
        DirectoryLogin directory = directories.getIfAvailable();
        if (user.getPassword() != null && (directory == null || !directory.isDirectoryManaged(userId))) {
            out.add(Factor.PASSWORD);
        }
        if (user.getEmail() != null) {
            out.add(Factor.EMAIL);
        }
        if (user.getPhoneNumber() != null) {
            out.add(Factor.PHONE);
        }
        return out;
    }

    public int accountClass(String userId) {
        return accountClass(enrolled(userId));
    }

    public int sessionStrength(String sessionId, Collection<String> amr) {
        Integer recorded = sessions.authStrength(sessionId);
        return recorded != null ? recorded : amrStrength(amr);
    }

    public void recordAuthentication(String sessionId, Collection<String> amr, int strength) {
        sessions.recordAuthentication(sessionId, amr, strength);
    }

    public boolean factorEnrolled(String sessionId, String userId, int classBefore, String method) {
        if (sessionId != null) {
            sessions.raiseStrength(sessionId, methodStrength(method));
        }
        return accountClass(userId) > classBefore;
    }

    public boolean permits(Operation operation, AuthPrincipal principal, boolean fresh) {
        String userId = principal.getSubject();
        return userId != null && permits(operation, enrolled(userId), sessionStrength(principal.getSessionId(), principal.getAmr()), fresh);
    }

    public static boolean acceptsErasureConfirmation(String method) {
        return SecondFactors.PASSWORD.equals(method) || SecondFactors.WEBAUTHN.equals(method);
    }

    public boolean erasureNeedsConfirmation(UserEntity user) {
        boolean password = user.getPassword() != null && !user.getPassword().isBlank();
        return password || webauthn.hasCredentials(String.valueOf(user.getId()));
    }

    public boolean issuesRecoveryCodesOnEnrollment(SsoClientPolicy.Policy policy, String userId) {
        return policy.recovery() && factors.recovery().remaining(userId) == 0;
    }

    @Override
    public boolean satisfied(AuthPrincipal principal) {
        return permits(Operation.MANAGE_FACTORS, principal, true);
    }

    @Override
    public boolean acrSatisfied(AuthPrincipal principal, String requiredAcr) {
        return acrSatisfied(principal.getAcr(), principal.getAmr(), requiredAcr);
    }
}
